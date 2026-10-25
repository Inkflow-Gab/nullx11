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
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatusOverlay: TextView
    private lateinit var tvStatusCapture: TextView
    private lateinit var btnUpdate: TextView
    private lateinit var tvUpdateInfo: TextView

    private var availableUpdate: Updater.Info? = null
    private var busy = false

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
        val btnBattery = findViewById<TextView>(R.id.btnBattery)
        btnUpdate = findViewById(R.id.btnUpdate)
        tvUpdateInfo = findViewById(R.id.tvUpdateInfo)
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

        // One UI aggressively kills background services — ask the user once
        // to exempt NullX11 so the bubble + projection stay alive.
        btnBattery.setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                toast(getString(R.string.update_failed, e.message ?: "battery"))
            }
        }

        btnUpdate.setOnClickListener {
            val upd = availableUpdate
            if (upd != null) downloadAndInstall(upd) else checkUpdate(true)
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
        checkUpdate(manual = false)
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

    // ------------------------------------------------------------- updates

    /**
     * Reads version.json from the repo (updated by the release CI after
     * every tagged build) and compares versionCode with the installed one.
     */
    private fun checkUpdate(manual: Boolean) {
        if (busy) return
        busy = true
        if (manual) tvUpdateInfo.text = getString(R.string.update_checking)
        Thread {
            val info = Updater.fetch()
            val cur = Updater.currentCode(this)
            val newer = info?.takeIf { it.code > cur }
            runOnUiThread {
                busy = false
                availableUpdate = newer
                when {
                    newer != null -> {
                        btnUpdate.text = getString(R.string.btn_update_now)
                        val notes = if (newer.notes.isNotBlank()) "\n${newer.notes}" else ""
                        tvUpdateInfo.text = getString(R.string.update_available_line, newer.name) + notes
                    }
                    info != null -> {
                        btnUpdate.text = getString(R.string.btn_check_updates)
                        if (manual) tvUpdateInfo.text = getString(R.string.update_uptodate, info.name)
                    }
                    manual -> tvUpdateInfo.text = getString(R.string.update_failed, "connection")
                }
            }
        }.start()
    }

    private fun downloadAndInstall(info: Updater.Info) {
        if (busy) return
        busy = true
        btnUpdate.text = getString(R.string.update_downloading)
        tvUpdateInfo.text = ""
        Thread {
            try {
                val f = File(cacheDir, "nullx11-update.apk")
                Updater.open(info.apkUrl).use { input ->
                    f.outputStream().use { output -> input.copyTo(output) }
                }
                runOnUiThread {
                    busy = false
                    btnUpdate.text = getString(R.string.btn_check_updates)
                    install(f)
                }
            } catch (e: Exception) {
                android.util.Log.e("nullx11", "download update failed", e)
                runOnUiThread {
                    busy = false
                    btnUpdate.text = getString(R.string.btn_check_updates)
                    tvUpdateInfo.text = getString(R.string.update_failed, e.message ?: "download")
                }
            }
        }.start()
    }

    private fun install(f: File) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (_: Exception) {}
                toast(getString(R.string.allow_unknown))
                return
            }
            val uri = FileProvider.getUriForFile(this, "com.nullx11.official.files", f)
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            startActivity(i)
            toast(getString(R.string.update_prompt))
        } catch (e: Exception) {
            android.util.Log.e("nullx11", "install launch failed", e)
            tvUpdateInfo.text = getString(R.string.update_failed, e.message ?: "install")
        }
    }

    // ---------------------------------------------------------------- misc

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
