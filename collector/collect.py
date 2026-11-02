#!/usr/bin/env python3
"""
POGOH station availability collector.

Polls the public GBFS feed (PBSC, CC-BY-4.0) every INTERVAL seconds, aligned to the clock
(e.g. :00, :02, :04 ...), and appends one row per station per poll to data/status/YYYY-MM-DD.csv (UTC days).
Station metadata (name, lat, lon, capacity) goes to data/stations.csv, refreshed every hour.

Runs for at most MAX_MINUTES, committing + pushing every COMMIT_EVERY minutes, then exits cleanly
so GitHub Actions' 6-hour job limit never kills it mid-write. Stdlib only.
"""
import csv
import json
import os
import subprocess
import sys
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

BASE = os.environ.get("GBFS_BASE", "https://pittsburgh.publicbikesystem.net/customer/gbfs/v2/")
INTERVAL = int(os.environ.get("INTERVAL", "120"))
MAX_MINUTES = float(os.environ.get("MAX_MINUTES", "345"))
COMMIT_EVERY = float(os.environ.get("COMMIT_EVERY", "30"))
STOP_AFTER = os.environ.get("STOP_AFTER", "")          # e.g. 2026-11-09T23:59:00Z ; empty = no end
PUSH = os.environ.get("PUSH", "1") == "1"
DATA = Path(os.environ.get("DATA_DIR", "data"))

STATUS_COLS = [
    "poll_ts_utc", "feed_last_updated_utc", "station_id",
    "bikes", "ebikes", "classic_bikes", "bikes_disabled", "docks", "docks_disabled",
    "is_installed", "is_renting", "is_returning", "last_reported_utc",
]
STATION_COLS = ["station_id", "name", "lat", "lon", "capacity", "first_seen_utc", "last_seen_utc"]


def log(*a):
    print(datetime.now(timezone.utc).strftime("%H:%M:%S"), *a, flush=True)


def get(url, tries=3):
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "pogoh-collector/1.0 (research)"})
            with urllib.request.urlopen(req, timeout=20) as r:
                return json.load(r)
        except Exception as e:
            log(f"fetch failed ({i+1}/{tries}) {url}: {e}")
            time.sleep(5 * (i + 1))
    return None


def feed_urls():
    info, status = BASE + "en/station_information", BASE + "en/station_status"
    d = get(BASE + "gbfs.json")
    try:
        data = d["data"]
        lang = "en" if "en" in data else next(iter(data))
        for f in data[lang]["feeds"]:
            if f["name"] == "station_information":
                info = f["url"]
            elif f["name"] == "station_status":
                status = f["url"]
    except Exception:
        log("discovery failed, using default feed paths")
    return info, status


def iso(ts):
    if ts in (None, ""):
        return ""
    try:
        if isinstance(ts, str) and not ts.isdigit():
            return ts                                   # already ISO (GBFS v3)
        return datetime.fromtimestamp(int(ts), timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    except Exception:
        return str(ts)


def b(v):
    if isinstance(v, bool):
        return int(v)
    try:
        return int(v)
    except Exception:
        return ""


def write_status(doc, poll_ts):
    stations = doc.get("data", {}).get("stations", [])
    day = poll_ts[:10]
    path = DATA / "status" / f"{day}.csv"
    path.parent.mkdir(parents=True, exist_ok=True)
    new = not path.exists()
    feed_ts = iso(doc.get("last_updated"))
    with path.open("a", newline="") as fh:
        w = csv.writer(fh)
        if new:
            w.writerow(STATUS_COLS)
        for s in stations:
            types = s.get("num_bikes_available_types") or {}
            w.writerow([
                poll_ts, feed_ts, s.get("station_id"),
                s.get("num_bikes_available", s.get("num_vehicles_available", "")),
                types.get("ebike", ""), types.get("mechanical", types.get("classic", "")),
                s.get("num_bikes_disabled", s.get("num_vehicles_disabled", "")),
                s.get("num_docks_available", ""), s.get("num_docks_disabled", ""),
                b(s.get("is_installed")), b(s.get("is_renting")), b(s.get("is_returning")),
                iso(s.get("last_reported")),
            ])
    return len(stations)


def write_stations(doc, poll_ts):
    path = DATA / "stations.csv"
    path.parent.mkdir(parents=True, exist_ok=True)
    rows = {}
    if path.exists():
        with path.open() as fh:
            for r in csv.DictReader(fh):
                rows[r["station_id"]] = r
    for s in doc.get("data", {}).get("stations", []):
        sid = str(s.get("station_id"))
        prev = rows.get(sid, {})
        rows[sid] = {
            "station_id": sid, "name": s.get("name", ""), "lat": s.get("lat", ""), "lon": s.get("lon", ""),
            "capacity": s.get("capacity", ""),
            "first_seen_utc": prev.get("first_seen_utc") or poll_ts, "last_seen_utc": poll_ts,
        }
    with path.open("w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=STATION_COLS)
        w.writeheader()
        for sid in sorted(rows, key=lambda x: (len(x), x)):
            w.writerow(rows[sid])


def git_sync(msg):
    if not PUSH:
        return
    try:
        subprocess.run(["git", "add", str(DATA)], check=True)
        if subprocess.run(["git", "diff", "--cached", "--quiet"]).returncode == 0:
            return
        subprocess.run(["git", "commit", "-qm", msg], check=True)
        for i in range(4):
            subprocess.run(["git", "pull", "-q", "--rebase", "-X", "theirs", "origin", "main"])
            if subprocess.run(["git", "push", "-q", "origin", "HEAD:main"]).returncode == 0:
                log("pushed:", msg)
                return
            time.sleep(10 * (i + 1))
        log("push failed after retries (data kept locally, next sync will retry)")
    except Exception as e:
        log("git sync error:", e)


def main():
    stop_at = None
    if STOP_AFTER:
        stop_at = datetime.fromisoformat(STOP_AFTER.replace("Z", "+00:00")).timestamp()
        if time.time() >= stop_at:
            log("past STOP_AFTER, nothing to do")
            return

    start = time.time()
    deadline = start + MAX_MINUTES * 60
    if stop_at:
        deadline = min(deadline, stop_at)
    info_url, status_url = feed_urls()
    last_commit = time.time()
    last_info = 0.0
    polls = 0

    while True:
        now = time.time()
        if now >= deadline:
            break
        poll_ts = datetime.fromtimestamp(now, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

        if now - last_info > 3600:
            doc = get(info_url)
            if doc:
                write_stations(doc, poll_ts)
                last_info = now

        doc = get(status_url)
        if doc:
            n = write_status(doc, poll_ts)
            polls += 1
            log(f"poll {polls}: {n} stations")
        else:
            log("poll skipped (feed unavailable)")

        if time.time() - last_commit > COMMIT_EVERY * 60:
            git_sync(f"data: {poll_ts}")
            last_commit = time.time()

        # sleep to the next clock-aligned slot
        nxt = (int(time.time()) // INTERVAL + 1) * INTERVAL
        if nxt >= deadline:
            break
        time.sleep(max(1, nxt - time.time()))

    git_sync(f"data: run end {datetime.now(timezone.utc):%Y-%m-%dT%H:%M:%SZ} ({polls} polls)")
    log(f"done, {polls} polls")


if __name__ == "__main__":
    sys.exit(main())
