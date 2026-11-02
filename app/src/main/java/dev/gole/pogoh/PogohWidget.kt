package dev.gole.pogoh

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.view.View
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PogohWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = refresh(ctx)

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_REFRESH) refresh(ctx)
    }

    private fun refresh(ctx: Context) {
        val pending = goAsync()
        render(ctx) { it.setTextViewText(R.id.status, "Updating…") }
        Thread {
            try {
                update(ctx.applicationContext)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun update(ctx: Context) {
        if (!hasLocationPermission(ctx)) {
            render(ctx, full = true) { v ->
                v.setTextViewText(R.id.status, "Open app → allow location")
                hideRows(v)
            }
            return
        }
        val loc = currentLocation(ctx)
        if (loc == null) {
            render(ctx) { it.setTextViewText(R.id.status, "No GPS fix — tap ⟳") }
            return
        }
        val all = try {
            Pogoh.stations(loc.latitude, loc.longitude)
        } catch (e: Exception) {
            render(ctx) { it.setTextViewText(R.id.status, "Feed error — tap ⟳") }
            return
        }
        val stations = Pogoh.withBikes(all, 3)
        val map = try {
            buildMap(ctx, loc.latitude, loc.longitude, all, stations)
        } catch (e: Exception) {
            null
        }

        val time = SimpleDateFormat("h:mm a", Locale.US).format(Date())
        render(ctx, full = true) { v ->
            v.setTextViewText(R.id.status, if (stations.isEmpty()) "No bikes nearby · $time" else "Updated $time")
            if (map != null) {
                v.setImageViewBitmap(R.id.map, map)
                // Tap map -> Google Maps around you
                val open = Intent(Intent.ACTION_VIEW, Uri.parse("geo:${loc.latitude},${loc.longitude}?z=16"))
                v.setOnClickPendingIntent(
                    R.id.map,
                    PendingIntent.getActivity(ctx, 99, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                )
            }
            for (i in 0 until 3) {
                val row = ROWS[i]
                val s = stations.getOrNull(i)
                if (s == null) {
                    v.setViewVisibility(row[0], View.INVISIBLE); continue
                }
                v.setViewVisibility(row[0], View.VISIBLE)
                v.setTextViewText(row[1], Pogoh.arrow(s.bearingDeg))
                v.setTextViewText(row[2], s.name)
                val bikes = if (s.ebikes > 0) "${s.bikes} bikes (${s.ebikes}⚡)" else "${s.bikes} bikes"
                v.setTextViewText(
                    row[3],
                    "${Pogoh.distanceText(s.distanceM)} ${Pogoh.compass(s.bearingDeg)} · ~${Pogoh.walkMin(s.distanceM)} min · $bikes"
                )
                // Tap a row -> walking directions in Google Maps
                val nav = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${s.lat},${s.lon}&travelmode=walking")
                )
                v.setOnClickPendingIntent(
                    row[0],
                    PendingIntent.getActivity(ctx, 100 + i, nav, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                )
            }
        }
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, opts: Bundle) {
        refresh(ctx) // widget was resized -> redraw map at the new size
    }

    /** Sizes the map bitmap to the widget's current size (portrait: min width × max height). */
    private fun buildMap(
        ctx: Context, lat: Double, lon: Double, all: List<NearbyStation>, top: List<NearbyStation>,
    ): Bitmap {
        val mgr = AppWidgetManager.getInstance(ctx)
        val id = mgr.getAppWidgetIds(ComponentName(ctx, PogohWidget::class.java)).first()
        val o = mgr.getAppWidgetOptions(id)
        val wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 320).takeIf { it > 0 } ?: 320
        val hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 280).takeIf { it > 0 } ?: 280
        // widget padding 24 + header ~24 + map margins 12 + three 40dp rows
        val mapWDp = (wDp - 24).coerceAtLeast(150)
        val mapHDp = (hDp - 24 - 24 - 12 - 120).coerceIn(90, 400)
        // Cap pixels so the bitmap stays well under the ~1 MB widget transfer limit.
        val screen = ctx.resources.displayMetrics.density
        val d = minOf(screen, 700f / mapWDp, 420f / mapHDp)
        return MapRenderer.render(
            ctx, (mapWDp * d).toInt(), (mapHDp * d).toInt(), d, lat, lon, all, top,
        )
    }

    private fun hideRows(v: RemoteViews) = ROWS.forEach { v.setViewVisibility(it[0], View.INVISIBLE) }

    /** full=true replaces the whole widget; false only patches status (keeps last rows on screen). Builds a fresh RemoteViews (with the refresh tap wired), applies [edit], pushes to every widget instance. */
    private fun render(ctx: Context, full: Boolean = false, edit: (RemoteViews) -> Unit) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, PogohWidget::class.java))
        if (ids.isEmpty()) return
        val v = RemoteViews(ctx.packageName, R.layout.widget_pogoh)
        val refresh = Intent(ctx, PogohWidget::class.java).setAction(ACTION_REFRESH)
        v.setOnClickPendingIntent(
            R.id.header,
            PendingIntent.getBroadcast(ctx, 0, refresh, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        )
        edit(v)
        if (full) mgr.updateAppWidget(ids, v) else mgr.partiallyUpdateAppWidget(ids, v)
    }

    companion object {
        const val ACTION_REFRESH = "dev.gole.pogoh.REFRESH"

        private val ROWS = arrayOf(
            intArrayOf(R.id.row1, R.id.arrow1, R.id.name1, R.id.info1),
            intArrayOf(R.id.row2, R.id.arrow2, R.id.name2, R.id.info2),
            intArrayOf(R.id.row3, R.id.arrow3, R.id.name3, R.id.info3),
        )

        fun hasLocationPermission(ctx: Context) =
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** Recent cached fix if < 2 min old, otherwise a fresh fix (6 s timeout), otherwise any cached fix. */
        @SuppressLint("MissingPermission")
        fun currentLocation(ctx: Context): Location? {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

            val cached = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
            if (cached != null && System.currentTimeMillis() - cached.time < 120_000) return cached

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && providers.isNotEmpty()) {
                val latch = CountDownLatch(1)
                var fresh: Location? = null
                val exec = Executors.newSingleThreadExecutor()
                val cancel = CancellationSignal()
                try {
                    lm.getCurrentLocation(providers.first(), cancel, exec) { l -> fresh = l; latch.countDown() }
                    latch.await(6, TimeUnit.SECONDS)
                } catch (_: Exception) {
                } finally {
                    cancel.cancel(); exec.shutdown()
                }
                fresh?.let { return it }
            }
            return cached
        }

        fun requestRefresh(ctx: Context) =
            ctx.sendBroadcast(Intent(ctx, PogohWidget::class.java).setAction(ACTION_REFRESH))
    }
}
