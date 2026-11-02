# POGOH Nearby — Android home-screen widget

Shows the 3 nearest POGOH stations that have bikes right now: arrow + compass direction, distance, walk time, bike/e-bike count.

## Build & install
1. Open the `PogohWidget` folder in Android Studio (Koala or newer). Let it sync (it downloads Gradle 8.7 + AGP 8.5).
2. Phone: Settings → About → tap Build number 7× → Developer options → USB debugging ON. Plug in.
3. Press Run ▶. App "POGOH Nearby" installs and opens.
4. In the app: tap **1. Allow location**, then **2. Allow location all the time** (pick "Allow all the time" in the settings page).
5. Long-press home screen → Widgets → POGOH Nearby → drag it on.

## Use
- Tap the header (⟳) to refresh. Auto-refreshes every 30 min (Android's minimum for widgets).
- Tap a station row → Google Maps walking directions.
- Arrows are relative to **north**, not your phone's heading (home-screen widgets can't read the compass live).

## Data
POGOH public GBFS feed by PBSC (CC-BY-4.0): `https://pittsburgh.publicbikesystem.net/customer/gbfs/v2/gbfs.json`.
Counts come from `station_status` (`num_bikes_available`, `num_bikes_available_types.ebike`).
