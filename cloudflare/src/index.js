// POGOH station availability collector — Cloudflare Worker (free plan).
// Every minute: read the public GBFS station_status feed (PBSC, CC-BY-4.0), write a row to `changes`
// for every station whose availability changed, and one row to `polls`.
//
// HTTP:
//   GET /health                      public; last poll + coverage over the last 24 h
//   GET /export?table=changes&from=2026-11-03&to=2026-11-04   (Authorization: Bearer <EXPORT_TOKEN>)
//        table = changes | polls | stations ; from/to = ISO date/time or unix seconds ; CSV out
//        max 50,000 rows per call; if more, response header X-Next-From gives the next `from`.

const FIELDS = [
  "bikes", "ebikes", "classic", "bikes_disabled", "docks", "docks_disabled",
  "is_installed", "is_renting", "is_returning",
];

export default {
  async scheduled(event, env, ctx) {
    ctx.waitUntil(poll(env, event.scheduledTime));
  },

  async fetch(req, env) {
    const url = new URL(req.url);
    try {
      if (url.pathname === "/health") return health(env);
      if (url.pathname === "/export") return exportCsv(req, url, env);
      return new Response("POGOH collector. See /health\n", { headers: { "content-type": "text/plain" } });
    } catch (e) {
      return new Response("error: " + e.message + "\n", { status: 500 });
    }
  },
};

// ---------------------------------------------------------------- collection

async function poll(env, scheduledTime) {
  const ts = Math.floor(scheduledTime / 60000) * 60;
  const base = env.GBFS_BASE;
  try {
    const status = await getJson(base + "en/station_status");
    const stations = status?.data?.stations ?? [];
    if (!stations.length) throw new Error("no stations in feed");

    const prev = new Map();
    const { results } = await env.DB.prepare("SELECT * FROM latest").all();
    for (const r of results) prev.set(r.station_id, r);

    const stmts = [];
    let changed = 0;
    let unknown = false;
    for (const s of stations) {
      const row = normalize(s);
      const old = prev.get(row.station_id);
      if (!old) unknown = true;
      if (old && FIELDS.every((f) => old[f] === row[f])) continue;
      changed++;
      stmts.push(
        env.DB.prepare(
          `INSERT INTO changes (ts, station_id, ${FIELDS.join(", ")}, last_reported, types_json)
           VALUES (?, ?, ${FIELDS.map(() => "?").join(", ")}, ?, ?)`
        ).bind(ts, row.station_id, ...FIELDS.map((f) => row[f]), row.last_reported, row.types_json),
        env.DB.prepare(
          `INSERT INTO latest (station_id, ts, ${FIELDS.join(", ")})
           VALUES (?, ?, ${FIELDS.map(() => "?").join(", ")})
           ON CONFLICT(station_id) DO UPDATE SET ts = excluded.ts,
           ${FIELDS.map((f) => `${f} = excluded.${f}`).join(", ")}`
        ).bind(row.station_id, ts, ...FIELDS.map((f) => row[f]))
      );
    }
    stmts.push(
      env.DB.prepare(
        "INSERT OR REPLACE INTO polls (ts, ok, feed_ts, n_stations, n_changed, error) VALUES (?, 1, ?, ?, ?, NULL)"
      ).bind(ts, toUnix(status.last_updated), stations.length, changed)
    );

    // Station metadata every 6 h, or immediately when a station we haven't seen appears.
    if (unknown || ts % 21600 === 0) stmts.push(...(await stationStmts(env, base, ts)));

    await env.DB.batch(stmts);
  } catch (e) {
    await env.DB.prepare(
      "INSERT OR REPLACE INTO polls (ts, ok, feed_ts, n_stations, n_changed, error) VALUES (?, 0, NULL, NULL, NULL, ?)"
    ).bind(ts, String(e.message ?? e).slice(0, 500)).run();
  }
}

async function stationStmts(env, base, ts) {
  const info = await getJson(base + "en/station_information");
  return (info?.data?.stations ?? []).map((s) =>
    env.DB.prepare(
      `INSERT INTO stations (station_id, name, lat, lon, capacity, first_seen, last_seen)
       VALUES (?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(station_id) DO UPDATE SET name = excluded.name, lat = excluded.lat, lon = excluded.lon,
       capacity = excluded.capacity, last_seen = excluded.last_seen`
    ).bind(String(s.station_id), s.name ?? null, num(s.lat), num(s.lon), int(s.capacity), ts, ts)
  );
}

function normalize(s) {
  // PBSC v2 uses an object {"mechanical": n, "ebike": n}; GBFS v3 uses vehicle_types_available [{vehicle_type_id, count}].
  let ebikes = null, classic = null, types = s.num_bikes_available_types ?? s.vehicle_types_available ?? null;
  if (types && !Array.isArray(types)) {
    ebikes = int(types.ebike ?? types.electric ?? types.e_bike);
    classic = int(types.mechanical ?? types.classic ?? types.bike);
  } else if (Array.isArray(types)) {
    for (const t of types) {
      const id = String(t.vehicle_type_id ?? "").toLowerCase();
      if (/elec|ebike|e-bike|efit/.test(id)) ebikes = (ebikes ?? 0) + int(t.count);
      else classic = (classic ?? 0) + int(t.count);
    }
  }
  return {
    station_id: String(s.station_id),
    bikes: int(s.num_bikes_available ?? s.num_vehicles_available),
    ebikes,
    classic,
    bikes_disabled: int(s.num_bikes_disabled ?? s.num_vehicles_disabled),
    docks: int(s.num_docks_available),
    docks_disabled: int(s.num_docks_disabled),
    is_installed: flag(s.is_installed),
    is_renting: flag(s.is_renting),
    is_returning: flag(s.is_returning),
    last_reported: toUnix(s.last_reported),
    types_json: types ? JSON.stringify(types) : null,
  };
}

async function getJson(url) {
  let last;
  for (let i = 0; i < 2; i++) {
    try {
      const r = await fetch(url, {
        headers: { "user-agent": "pogoh-collector/1.0 (research)", accept: "application/json" },
        cf: { cacheTtl: 0 },
      });
      if (!r.ok) throw new Error(`HTTP ${r.status} ${url}`);
      return await r.json();
    } catch (e) {
      last = e;
    }
  }
  throw last;
}

const int = (v) => (v === undefined || v === null || v === "" || isNaN(Number(v)) ? null : Math.trunc(Number(v)));
const num = (v) => (v === undefined || v === null || v === "" || isNaN(Number(v)) ? null : Number(v));
const flag = (v) => (v === undefined || v === null ? null : v === true || Number(v) === 1 ? 1 : 0);
function toUnix(v) {
  if (v === undefined || v === null || v === "") return null;
  if (typeof v === "number" || /^\d+$/.test(String(v))) return Math.trunc(Number(v));
  const t = Date.parse(v);
  return isNaN(t) ? null : Math.floor(t / 1000);
}

// ---------------------------------------------------------------- HTTP

async function health(env) {
  const now = Math.floor(Date.now() / 1000);
  const last = await env.DB.prepare("SELECT * FROM polls ORDER BY ts DESC LIMIT 1").first();
  const day = await env.DB.prepare(
    "SELECT COUNT(*) AS polls, SUM(ok) AS ok, SUM(n_changed) AS changes FROM polls WHERE ts > ?"
  ).bind(now - 86400).first();
  const first = await env.DB.prepare("SELECT ts FROM polls ORDER BY ts ASC LIMIT 1").first();
  const body = {
    now: iso(now),
    collecting_since: first ? iso(first.ts) : null,
    last_poll: last ? { ...last, ts: iso(last.ts), age_s: now - last.ts } : null,
    last_24h: { polls: day.polls, ok: day.ok, expected: 1440, changes: day.changes },
  };
  return new Response(JSON.stringify(body, null, 2) + "\n", { headers: { "content-type": "application/json" } });
}

const TABLES = {
  changes: { cols: ["ts", "station_id", ...FIELDS, "last_reported", "types_json"], key: "ts" },
  polls: { cols: ["ts", "ok", "feed_ts", "n_stations", "n_changed", "error"], key: "ts" },
  stations: { cols: ["station_id", "name", "lat", "lon", "capacity", "first_seen", "last_seen"], key: null },
};
const MAX_ROWS = 50000;

async function exportCsv(req, url, env) {
  if (!env.EXPORT_TOKEN || req.headers.get("authorization") !== `Bearer ${env.EXPORT_TOKEN}`) {
    return new Response("unauthorized\n", { status: 401 });
  }
  const name = url.searchParams.get("table") ?? "changes";
  const t = TABLES[name];
  if (!t) return new Response("unknown table\n", { status: 400 });

  let rows;
  let next = null;
  if (!t.key) {
    ({ results: rows } = await env.DB.prepare(`SELECT ${t.cols.join(", ")} FROM ${name}`).all());
  } else {
    const from = parseTime(url.searchParams.get("from")) ?? 0;
    const to = parseTime(url.searchParams.get("to")) ?? 4102444800;
    ({ results: rows } = await env.DB.prepare(
      `SELECT ${t.cols.join(", ")} FROM ${name} WHERE ts >= ? AND ts < ? ORDER BY ts LIMIT ?`
    ).bind(from, to, MAX_ROWS + 1).all());
    if (rows.length > MAX_ROWS) {
      // don't split a minute across pages
      const cut = rows[MAX_ROWS].ts;
      rows = rows.filter((r) => r.ts < cut);
      next = cut;
    }
  }
  const esc = (v) => (v === null || v === undefined ? "" : /[",\n]/.test(String(v)) ? `"${String(v).replace(/"/g, '""')}"` : String(v));
  const csv = [t.cols.join(","), ...rows.map((r) => t.cols.map((c) => esc(r[c])).join(","))].join("\n") + "\n";
  const headers = { "content-type": "text/csv", "x-rows": String(rows.length) };
  if (next !== null) headers["x-next-from"] = String(next);
  return new Response(csv, { headers });
}

function parseTime(v) {
  if (!v) return null;
  if (/^\d+$/.test(v)) return Number(v);
  const t = Date.parse(v.length === 10 ? v + "T00:00:00Z" : v);
  return isNaN(t) ? null : Math.floor(t / 1000);
}
const iso = (s) => new Date(s * 1000).toISOString().replace(".000", "");
