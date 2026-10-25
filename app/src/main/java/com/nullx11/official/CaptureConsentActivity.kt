package com.nullx11.official

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Invisible helper the overlay service opens mid-air: shows the system
 * MediaProjection consent dialog over whatever app the user is in and hands
 * the result to the service, so a capture that lost its projection just
 * continues instead of telling the user to "turn it on again".
 */
class CaptureConsentActivity : Activity() {

    override fun onResume() {
        super.onResume()
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), 9001)
        } catch (e: Exception) {
            Log.e("nullx11", "consent dialog failed", e)
            TransOverlayService.pendingScreenTranslate = false
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 9001) {
            if (resultCode == RESULT_OK && data != null) {
                Log.d("nullx11", "consent OK from overlay recovery")
                val svc = Intent(this, TransOverlayService::class.java)
                    .putExtra(TransOverlayService.EXTRA_CODE, resultCode)
                    .putExtra(TransOverlayService.EXTRA_DATA, data)
                    .putExtra(TransOverlayService.EXTRA_AUTORUN, true)
                ContextCompat.startForegroundService(this, svc)
            } else {
                Log.d("nullx11", "consent DENIED from overlay recovery")
                TransOverlayService.pendingScreenTranslate = false
            }
        }
        finish()
    }
}
