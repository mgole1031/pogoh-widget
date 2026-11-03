# Year-long POGOH collector (Cloudflare Worker + D1)

Every minute: reads POGOH's public GBFS `station_status`, and stores
- `polls` — one row per minute (coverage log; a missing minute = collector was down)
- `changes` — one row whenever a station's counts change (bikes, ebikes, classic, docks, disabled, is_renting…)
- `stations` — name, lat, lon, capacity (refreshed every 6 h)

To rebuild the full minute-by-minute picture for a station: forward-fill `changes` over the `polls` minutes.

## Endpoints
- `GET /health` — last poll, last-24h coverage (public)
- `GET /export?table=changes&from=2026-11-03&to=2026-11-10` with header `Authorization: Bearer <EXPORT_TOKEN>` → CSV
  (tables: changes, polls, stations; ≤50k rows per call; header `X-Next-From` = next page's `from`)

```python
import pandas as pd, requests, io
U, H = "https://pogoh-collector.<you>.workers.dev", {"Authorization": "Bearer <EXPORT_TOKEN>"}
def get(table, frm=0, to=4102444800):
    out = []
    while True:
        r = requests.get(f"{U}/export", params={"table": table, "from": frm, "to": to}, headers=H); r.raise_for_status()
        out.append(pd.read_csv(io.StringIO(r.text)))
        if "x-next-from" not in r.headers: return pd.concat(out)
        frm = r.headers["x-next-from"]
changes, polls, stations = get("changes"), get("polls"), get("stations")
```

Free-plan budget (approx.): ~1,440 polls + a few thousand changes/day → well under D1's daily write limit;
~1 GB/year, under the 5 GB free storage.
