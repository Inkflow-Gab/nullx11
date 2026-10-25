package com.nullx11.official

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatusOverlay: TextView
    private lateinit var tvStatusCapture: TextView

    /** MediaProjection consent dialog → hand the result to the overlay service. */
    private val consentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                val svc = Intent(this, TransOverlayService::class.java)
                    .putExtra(TransOverlayService.EXTRA_CODE, result.resultCode)
                    .putExtra(TransOverlayService.EXTRA_DATA, data)
                ContextCompat.startForegroundService(this, svc)
                toast(getString(R.string.toast_capture_granted))
            } else {
                toast(getString(R.string.toast_capture_denied))
            }
            refresh()
        }

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnOpenOverlay = findViewById<TextView>(R.id.btnOpenOverlay)
        val btnGrantCapture = findViewById<TextView>(R.id.btnGrantCapture)
        val btnReleases = findViewById<TextView>(R.id.btnReleases)
        tvStatusOverlay = findViewById(R.id.tvStatusOverlay)
        tvStatusCapture = findViewById(R.id.tvStatusCapture)

        btnOpenOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                toast(getString(R.string.toast_overlay_need_permission))
                openOverlaySettings()
                return@setOnClickListener
            }
            askForNotifications()
            ContextCompat.startForegroundService(
                this, Intent(this, TransOverlayService::class.java)
            )
            toast(getString(R.string.toast_overlay_on))
            refresh()
        }

        btnGrantCapture.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                toast(getString(R.string.toast_overlay_need_permission))
                openOverlaySettings()
                return@setOnClickListener
            }
            askForNotifications()
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            consentLauncher.launch(mpm.createScreenCaptureIntent())
        }

        btnReleases.setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/Inkflow-Gab/nullx11/releases")
                    )
                )
            } catch (_: Exception) {}
        }

        try {
            val v = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
            findViewById<TextView>(R.id.tvFooter).text =
                getString(R.string.footer_free) + "  ·  v" + v
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** Live service state → the two status pills. */
    private fun refresh() {
        val on = TransOverlayService.isRunning
        tvStatusOverlay.text = getString(if (on) R.string.status_on else R.string.status_off)
        tvStatusOverlay.setTextColor(getColor(if (on) R.color.nxOk else R.color.nxErr))

        val cap = TransOverlayService.hasProjection
        tvStatusCapture.text = getString(if (cap) R.string.capture_ok else R.string.capture_missing)
        tvStatusCapture.setTextColor(getColor(if (cap) R.color.nxOk else R.color.nxWarn))
    }

    private fun openOverlaySettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {}
    }

    /** Best effort — the ongoing notification must be visible on Android 13+. */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
