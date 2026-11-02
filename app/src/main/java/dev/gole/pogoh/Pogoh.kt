package dev.gole.pogoh

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** One station with live availability, already measured from the user. */
data class NearbyStation(
    val name: String,
    val lat: Double,
    val lon: Double,
    val bikes: Int,
    val ebikes: Int,
    val docks: Int,
    val distanceM: Double,
    val bearingDeg: Double,
)

/**
 * Reads POGOH's public GBFS feed (published by PBSC, licensed CC-BY-4.0).
 * Discovery file lists the station_information and station_status URLs.
 */
object Pogoh {
    private const val BASE = "https://pittsburgh.publicbikesystem.net/customer/gbfs/v2/"
    private const val DISCOVERY = BASE + "gbfs.json"

    fun nearest(userLat: Double, userLon: Double, count: Int = 3): List<NearbyStation> {
        val (infoUrl, statusUrl) = feedUrls()
        val info = getJson(infoUrl).getJSONObject("data").getJSONArray("stations")
        val status = getJson(statusUrl).getJSONObject("data").getJSONArray("stations")

        // station_id -> live status
        val live = HashMap<String, JSONObject>(status.length())
        for (i in 0 until status.length()) {
            val s = status.getJSONObject(i)
            live[s.getString("station_id")] = s
        }

        val out = ArrayList<NearbyStation>()
        for (i in 0 until info.length()) {
            val st = info.getJSONObject(i)
            val s = live[st.getString("station_id")] ?: continue
            if (!flag(s, "is_installed", true) || !flag(s, "is_renting", true)) continue

            val total = s.optInt("num_bikes_available", s.optInt("num_vehicles_available", 0))
            if (total <= 0) continue
            val ebikes = s.optJSONObject("num_bikes_available_types")?.optInt("ebike", 0) ?: 0

            val lat = st.getDouble("lat")
            val lon = st.getDouble("lon")
            out += NearbyStation(
                name = st.optString("name", "Station"),
                lat = lat, lon = lon,
                bikes = total, ebikes = ebikes,
                docks = s.optInt("num_docks_available", 0),
                distanceM = haversine(userLat, userLon, lat, lon),
                bearingDeg = bearing(userLat, userLon, lat, lon),
            )
        }
        return out.sortedBy { it.distanceM }.take(count)
    }

    /** Uses the discovery file; falls back to PBSC's standard paths if it changes shape. */
    private fun feedUrls(): Pair<String, String> {
        var info = BASE + "en/station_information"
        var status = BASE + "en/station_status"
        try {
            val data = getJson(DISCOVERY).getJSONObject("data")
            val lang = if (data.has("en")) "en" else data.keys().next()
            val feeds = data.getJSONObject(lang).getJSONArray("feeds")
            for (i in 0 until feeds.length()) {
                val f = feeds.getJSONObject(i)
                when (f.getString("name")) {
                    "station_information" -> info = f.getString("url")
                    "station_status" -> status = f.getString("url")
                }
            }
        } catch (_: Exception) { /* use fallbacks */ }
        return info to status
    }

    /** GBFS v1/v2 use 0/1, v3 uses booleans. */
    private fun flag(o: JSONObject, key: String, default: Boolean): Boolean {
        if (!o.has(key)) return default
        return when (val v = o.get(key)) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            else -> default
        }
    }

    private fun getJson(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/json")
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    // ---------- geometry ----------

    private const val R = 6_371_000.0

    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * R * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial compass bearing from point 1 to point 2, 0..360, 0 = north. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private val ARROWS = arrayOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")
    private val POINTS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    private fun octant(deg: Double) = (((deg + 22.5) % 360) / 45).toInt()
    fun arrow(deg: Double) = ARROWS[octant(deg)]
    fun compass(deg: Double) = POINTS[octant(deg)]

    fun distanceText(m: Double): String =
        if (m < 1000) "${(m / 10).roundToInt() * 10} m" else "%.1f km".format(m / 1000)

    /** ~80 m/min walking. */
    fun walkMin(m: Double): Int = maxOf(1, (m / 80).roundToInt())
}
