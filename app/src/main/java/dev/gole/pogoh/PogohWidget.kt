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
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
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
        val stations = try {
            Pogoh.nearest(loc.latitude, loc.longitude, 3)
        } catch (e: Exception) {
            render(ctx) { it.setTextViewText(R.id.status, "Feed error — tap ⟳") }
            return
        }

        val time = SimpleDateFormat("h:mm a", Locale.US).format(Date())
        render(ctx, full = true) { v ->
            v.setTextViewText(R.id.status, if (stations.isEmpty()) "No bikes nearby · $time" else "Updated $time")
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
