package com.nullx11.official

import android.content.Context
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app update detection: reads the version.json the release pipeline
 * commits to `main` after every tagged release, compares versionCode and
 * streams the release APK down for the installer.
 */
object Updater {
    private const val META_URL =
        "https://raw.githubusercontent.com/Inkflow-Gab/nullx11/main/version.json"

    data class Info(val code: Int, val name: String, val apkUrl: String, val notes: String)

    fun fetch(): Info? = try {
        val raw = URL(META_URL).readText(Charsets.UTF_8)
        val j = JSONObject(raw)
        val code = j.optInt("versionCode", 0)
        val url = j.optString("apkUrl", "")
        if (code > 0 && url.isNotBlank()) {
            Info(
                code,
                j.optString("versionName", code.toString()),
                url,
                j.optString("releaseNotes", "")
            )
        } else null
    } catch (e: Exception) {
        Log.e("nullx11", "fetch version.json failed", e)
        null
    }

    fun currentCode(ctx: Context): Int = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        PackageInfoCompat.getLongVersionCode(pi).toInt()
    } catch (e: Exception) {
        0
    }

    /** Redirect-following stream of the release APK asset. */
    fun open(url: String): InputStream {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 300000
        val code = c.responseCode
        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code — release asset unreachable")
        }
        return c.inputStream
    }
}
