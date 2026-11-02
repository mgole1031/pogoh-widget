package dev.gole.pogoh

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Setup screen: grants location (incl. background, so the widget can refresh itself), then you add the widget. */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 2, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "POGOH Nearby"
            textSize = 24f
        })
        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, pad, 0, pad)
        }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "1. Allow location"
            setOnClickListener {
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1
                )
            }
        })
        root.addView(Button(this).apply {
            text = "2. Allow location all the time"
            setOnClickListener { requestBackground() }
        })
        root.addView(Button(this).apply {
            text = "Refresh widget now"
            setOnClickListener { PogohWidget.requestRefresh(this@MainActivity) }
        })
        root.addView(TextView(this).apply {
            textSize = 13f
            setPadding(0, pad, 0, 0)
            text = "Then long-press your home screen → Widgets → POGOH Nearby.\n\n" +
                "Tap the widget header to refresh. Tap a station for walking directions.\n\n" +
                "Arrows point relative to NORTH (top of a north-up map), not to where your phone is facing.\n\n" +
                "Data: POGOH / PBSC public GBFS feed (CC-BY-4.0)."
        })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val fg = PogohWidget.hasLocationPermission(this)
        val bg = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        status.text = when {
            fg && bg -> "✅ Location ready. Add the widget to your home screen."
            fg -> "⚠️ Step 2 needed: choose \"Allow all the time\" so the widget can refresh in the background."
            else -> "Step 1: allow location."
        }
        if (fg) PogohWidget.requestRefresh(this)
    }

    private fun requestBackground() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!PogohWidget.hasLocationPermission(this)) {
            status.text = "Do step 1 first."; return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ only allows this via the settings page.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        onResume()
    }
}
