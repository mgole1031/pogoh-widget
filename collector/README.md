# POGOH availability dataset

Collected by `.github/workflows/collect.yml` → `collector/collect.py` from POGOH's public GBFS feed
(PBSC, CC-BY-4.0). Snapshot every 2 minutes, clock-aligned (:00, :02, …), until `STOP_AFTER`.

## Files
- `data/status/YYYY-MM-DD.csv` — one row per station per poll (UTC day)
  - `poll_ts_utc` when we polled · `feed_last_updated_utc` feed's own timestamp
  - `bikes` (total available), `ebikes`, `classic_bikes`, `bikes_disabled`
  - `docks` (empty docks), `docks_disabled`
  - `is_installed`, `is_renting`, `is_returning` (0/1) · `last_reported_utc` station's last check-in
- `data/stations.csv` — station_id, name, lat, lon, capacity (refreshed hourly)

## Load
```python
import pandas as pd, glob
df = pd.concat(pd.read_csv(f, parse_dates=["poll_ts_utc"]) for f in sorted(glob.glob("data/status/*.csv")))
st = pd.read_csv("data/stations.csv")
df = df.merge(st[["station_id", "name", "lat", "lon", "capacity"]], on="station_id")
df["t_local"] = df.poll_ts_utc.dt.tz_convert("America/New_York")
wide = df.pivot_table(index="poll_ts_utc", columns="station_id", values="bikes")  # time × station
```

## Notes
- Expect a few-minute gap roughly every 6 h (GitHub job handover) and occasional skipped polls if the feed hiccups. Reindex to a 2-min grid and forward-fill short gaps.
- Rebalancing trucks move bikes in bulk — large jumps aren't rider demand.
- Stop early: Actions tab → "Collect station data" → ⋯ → Disable workflow.
