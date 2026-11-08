package com.enigmacast.data.telemetry

import android.content.Context
import android.os.Build
import com.enigmacast.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import org.json.JSONObject

/**
 * Anonymous start statistics: on each app start one small JSON with device model, Android and
 * app version is posted in the background. A random id (made once, kept in prefs) is the only
 * identifier. Off when the user turns "Anonymous usage statistics" off. Failures are silent.
 */
object Telemetry {
    const val ENDPOINT = "https://api.velixatv.com/api/v1/telemetry/start"
    private const val FILE = "telemetry"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ID = "device_id"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Call once from Application.onCreate; never blocks and never throws. */
    fun onAppStart(context: Context) {
        val app = context.applicationContext
        Thread({ runCatching { send(app) } }, "telemetry").apply { isDaemon = true }.start()
    }

    private fun deviceId(context: Context): String {
        val p = prefs(context)
        p.getString(KEY_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        p.edit().putString(KEY_ID, id).apply()
        return id
    }

    internal fun payload(context: Context): JSONObject {
        val dm = context.resources.displayMetrics
        val w = maxOf(dm.widthPixels, dm.heightPixels)
        val h = minOf(dm.widthPixels, dm.heightPixels)
        return JSONObject()
            .put("device_id", deviceId(context))
            .put("manufacturer", Build.MANUFACTURER.orEmpty().take(64))
            .put("model", Build.MODEL.orEmpty().take(64))
            .put("brand", Build.BRAND.orEmpty().take(64))
            .put("device_name", Build.DEVICE.orEmpty().take(64))
            .put("android_version", Build.VERSION.RELEASE.orEmpty().take(16))
            .put("sdk_int", Build.VERSION.SDK_INT)
            .put("app_version", BuildConfig.VERSION_NAME.take(32))
            .put("app_version_code", BuildConfig.VERSION_CODE)
            .put("screen_resolution", "${w}x$h")
            .put("locale", Locale.getDefault().toLanguageTag().take(16))
    }

    private fun send(context: Context) {
        if (!isEnabled(context)) return
        val body = payload(context).toString().toByteArray(Charsets.UTF_8)
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }
}
