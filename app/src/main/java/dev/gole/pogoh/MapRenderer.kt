package dev.gole.pogoh

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Draws a small map bitmap for the widget: dark street tiles (CARTO, from OpenStreetMap data),
 * centred on the user, with every station in view as a marker showing its bike count.
 * Yellow = bikes available, grey = empty / not renting. The 3 nearest with bikes get a white ring.
 */
object MapRenderer {

    private const val TILE = 256.0
    private const val MIN_Z = 12
    private const val MAX_Z = 17
    private const val BG = 0xFF141A1F.toInt()

    fun render(
        ctx: Context,
        widthPx: Int,
        heightPx: Int,
        density: Float,          // bitmap px per dp
        userLat: Double,
        userLon: Double,
        all: List<NearbyStation>,
        top: List<NearbyStation>,
    ): Bitmap {
        // RGB_565 keeps the bitmap small enough to pass to the home screen (binder ~1 MB limit).
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        c.drawColor(BG)

        // Rounded corners: clip everything we draw after this.
        val radius = 14f * density
        c.clipPath(Path().apply {
            addRoundRect(RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat()), radius, radius, Path.Direction.CW)
        })
        c.drawColor(0xFF1E252B.toInt())

        val ux = worldX(userLon); val uy = worldY(userLat)
        val pad = 22f * density
        val halfW = widthPx / 2f - pad
        val halfH = heightPx / 2f - pad

        // Highest zoom where the 3 nearest-with-bikes all fit around the user (who stays centred).
        var z = MAX_Z
        if (top.isNotEmpty()) {
            val maxDx = top.maxOf { abs(worldX(it.lon) - ux) }
            val maxDy = top.maxOf { abs(worldY(it.lat) - uy) }
            while (z > MIN_Z) {
                val k = (1 shl z) * density
                if (maxDx * k <= halfW && maxDy * k <= halfH) break
                z--
            }
        }
        val scale = (1 shl z).toDouble()            // world px -> zoom-z px
        val cx = ux * scale; val cy = uy * scale     // centre, in zoom-z px
        fun toScreenX(wx: Double) = ((wx * scale - cx) * density + widthPx / 2.0).toFloat()
        fun toScreenY(wy: Double) = ((wy * scale - cy) * density + heightPx / 2.0).toFloat()

        drawTiles(ctx, c, z, cx, cy, widthPx, heightPx, density)

        // --- stations ---
        val topIds = top.map { it.lat to it.lon }.toSet()
        val r = 10f * density
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2.2f * density; color = Color.WHITE
        }
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1f * density; color = 0xAA000000.toInt()
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = 10.5f * density
        }
        val bounds = Rect()

        // Draw empty ones first so stations with bikes sit on top when they overlap.
        val visible = all.map { it to (toScreenX(worldX(it.lon)) to toScreenY(worldY(it.lat))) }
            .filter { (_, p) -> p.first in -r..(widthPx + r) && p.second in -r..(heightPx + r) }
            .sortedBy { (s, _) -> if (s.renting && s.bikes > 0) 1 else 0 }

        for ((s, p) in visible) {
            val (x, y) = p
            val hasBikes = s.renting && s.bikes > 0
            fill.color = if (hasBikes) 0xFFFFD84A.toInt() else 0xFF59636C.toInt()
            text.color = if (hasBikes) 0xFF141A1F.toInt() else 0xFFE6E6E6.toInt()
            c.drawCircle(x, y, r, fill)
            c.drawCircle(x, y, r, edge)
            if ((s.lat to s.lon) in topIds) c.drawCircle(x, y, r + 1.6f * density, ring)
            val label = if (s.renting) s.bikes.toString() else "×"
            text.getTextBounds(label, 0, label.length, bounds)
            c.drawText(label, x, y + bounds.height() / 2f, text)
        }

        // --- you ---
        val mx = widthPx / 2f; val my = heightPx / 2f
        fill.color = 0x404DA3FF
        c.drawCircle(mx, my, 14f * density, fill)
        fill.color = Color.WHITE
        c.drawCircle(mx, my, 7.5f * density, fill)
        fill.color = 0xFF4DA3FF.toInt()
        c.drawCircle(mx, my, 5.5f * density, fill)

        // --- north marker + attribution ---
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCCFFFFFF.toInt(); textSize = 9f * density; typeface = Typeface.DEFAULT_BOLD
        }
        c.drawText("N↑", 6f * density, 13f * density, small)
        small.typeface = Typeface.DEFAULT
        small.textSize = 7.5f * density
        small.color = 0x99FFFFFF.toInt()
        small.textAlign = Paint.Align.RIGHT
        c.drawText("© OpenStreetMap © CARTO", widthPx - 5f * density, heightPx - 4f * density, small)
        return bmp
    }

    // ---------- tiles ----------

    private fun drawTiles(
        ctx: Context, c: Canvas, z: Int, cx: Double, cy: Double, w: Int, h: Int, density: Float,
    ) {
        val halfWWorld = w / 2.0 / density
        val halfHWorld = h / 2.0 / density
        val x0 = floor((cx - halfWWorld) / TILE).toInt()
        val x1 = floor((cx + halfWWorld) / TILE).toInt()
        val y0 = floor((cy - halfHWorld) / TILE).toInt()
        val y1 = floor((cy + halfHWorld) / TILE).toInt()
        val n = 1 shl z

        val jobs = ArrayList<Triple<Int, Int, java.util.concurrent.Future<Bitmap?>>>()
        val pool = Executors.newFixedThreadPool(4)
        try {
            for (tx in x0..x1) for (ty in y0..y1) {
                if (ty < 0 || ty >= n) continue
                val wx = ((tx % n) + n) % n
                jobs += Triple(tx, ty, pool.submit<Bitmap?> { tile(ctx, z, wx, ty) })
            }
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val dst = RectF()
            for ((tx, ty, f) in jobs) {
                val t = runCatching { f.get(10, TimeUnit.SECONDS) }.getOrNull() ?: continue
                val left = ((tx * TILE - cx) * density + w / 2.0).toFloat()
                val top = ((ty * TILE - cy) * density + h / 2.0).toFloat()
                dst.set(left, top, left + (TILE * density).toFloat(), top + (TILE * density).toFloat())
                c.drawBitmap(t, null, dst, paint)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /** Disk-cached tile (tiles barely change; cache keeps refreshes fast and light on the tile server). */
    private fun tile(ctx: Context, z: Int, x: Int, y: Int): Bitmap? {
        val dir = File(ctx.cacheDir, "tiles").apply { mkdirs() }
        val f = File(dir, "${z}_${x}_${y}.png")
        val maxAge = 14L * 24 * 3600 * 1000
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < maxAge) {
            BitmapFactory.decodeFile(f.path)?.let { return it }
        }
        val sub = "abcd"[(x + y) % 4]
        val url = "https://$sub.basemaps.cartocdn.com/dark_all/$z/$x/$y@2x.png"
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "PogohNearby/1.1 (personal Android widget)")
            try {
                if (conn.responseCode !in 200..299) return null
                val bytes = conn.inputStream.use { it.readBytes() }
                runCatching { f.writeBytes(bytes) }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            if (f.exists()) BitmapFactory.decodeFile(f.path) else null
        }
    }

    // ---------- Web Mercator, world size 256 at zoom 0 ----------

    private fun worldX(lon: Double) = (lon + 180.0) / 360.0 * TILE

    private fun worldY(lat: Double): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return (1.0 - ln(tan(r) + 1.0 / kotlin.math.cos(r)) / Math.PI) / 2.0 * TILE
    }
}
