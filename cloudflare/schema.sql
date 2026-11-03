-- One row per poll (every minute). Proves coverage: a missing minute = collector was down.
CREATE TABLE IF NOT EXISTS polls (
  ts INTEGER PRIMARY KEY,          -- unix seconds, minute-aligned (UTC)
  ok INTEGER NOT NULL,             -- 1 = feed read fine
  feed_ts INTEGER,                 -- feed's own last_updated
  n_stations INTEGER,
  n_changed INTEGER,
  error TEXT
);

-- One row each time any station's availability changes (bike taken/returned/rebalanced/disabled).
CREATE TABLE IF NOT EXISTS changes (
  ts INTEGER NOT NULL,
  station_id TEXT NOT NULL,
  bikes INTEGER, ebikes INTEGER, classic INTEGER,
  bikes_disabled INTEGER, docks INTEGER, docks_disabled INTEGER,
  is_installed INTEGER, is_renting INTEGER, is_returning INTEGER,
  last_reported INTEGER,
  types_json TEXT                  -- raw per-type counts, in case the feed's format changes
);
CREATE INDEX IF NOT EXISTS changes_ts ON changes(ts);

-- Current state per station (used to detect changes).
CREATE TABLE IF NOT EXISTS latest (
  station_id TEXT PRIMARY KEY,
  ts INTEGER, bikes INTEGER, ebikes INTEGER, classic INTEGER,
  bikes_disabled INTEGER, docks INTEGER, docks_disabled INTEGER,
  is_installed INTEGER, is_renting INTEGER, is_returning INTEGER
);

-- Station metadata, refreshed every 6 h and whenever a new station appears.
CREATE TABLE IF NOT EXISTS stations (
  station_id TEXT PRIMARY KEY,
  name TEXT, lat REAL, lon REAL, capacity INTEGER,
  first_seen INTEGER, last_seen INTEGER
);
