package com.enigmacast.data.telemetry

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Base64
import com.enigmacast.BuildConfig
import com.enigmacast.domain.model.ReceiverConfig
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/**
 * Anonymous start statistics: on each app start one small JSON with device model, Android and
 * app version is posted in the background. A random id (made once, kept in prefs) is the only
 * identifier. Once per session, after the receiver answers, its brand, model and image are
 * read from OpenWebif `/api/about` and sent too. Off when the user turns "Anonymous usage
 * statistics" off. Failures are silent.
 */
object Telemetry {
    const val ENDPOINT = "https://api.velixatv.com/api/v1/telemetry/start"
    private const val FILE = "telemetry"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ID = "device_id"
    private const val KEY_RX_BRAND = "rx_brand"
    private const val KEY_RX_MODEL = "rx_model"
    private const val KEY_RX_IMAGE = "rx_image"
    private const val KEY_RX_IMAGE_VERSION = "rx_image_version"

    @SuppressLint("StaticFieldLeak") // application context only
    @Volatile private var appContext: Context? = null
    /** Host of the receiver already reported this session; a new host is reported again. */
    private val reportedHost = AtomicReference<String?>(null)

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Call once from Application.onCreate; never blocks and never throws. */
    fun onAppStart(context: Context) {
        val app = context.applicationContext
        appContext = app
        background { if (isEnabled(app)) post(payload(app)) }
    }

    /**
     * The receiver at [target] answered: read its identity once per session and send it.
     * Never blocks and never throws.
     */
    fun reportReceiver(target: ReceiverConfig) {
        val app = appContext ?: return
        val host = target.host.trim()
        if (host.isBlank()) return
        val previous = reportedHost.getAndSet(host)
        if (previous == host) return
        background {
            if (!isEnabled(app)) return@background
            val about = readAbout(target) ?: run { reportedHost.compareAndSet(host, null); return@background }
            prefs(app).edit()
                .putString(KEY_RX_BRAND, about[0]).putString(KEY_RX_MODEL, about[1])
                .putString(KEY_RX_IMAGE, about[2]).putString(KEY_RX_IMAGE_VERSION, about[3])
                .apply()
            post(receiverFields(app, JSONObject().put("device_id", deviceId(app)).put("event", "receiver")))
        }
    }

    private fun background(block: () -> Unit) {
        Thread({ runCatching(block) }, "telemetry").apply { isDaemon = true }.start()
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
        return receiverFields(
            context,
            JSONObject()
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
                .put("locale", Locale.getDefault().toLanguageTag().take(16)),
        )
    }

    /** Adds the last known receiver identity, when there is one. */
    private fun receiverFields(context: Context, json: JSONObject): JSONObject {
        val p = prefs(context)
        p.getString(KEY_RX_BRAND, null)?.takeIf { it.isNotBlank() }?.let { json.put("receiver_brand", it) }
        p.getString(KEY_RX_MODEL, null)?.takeIf { it.isNotBlank() }?.let { json.put("receiver_model", it) }
        p.getString(KEY_RX_IMAGE, null)?.takeIf { it.isNotBlank() }?.let { json.put("receiver_image", it) }
        p.getString(KEY_RX_IMAGE_VERSION, null)?.takeIf { it.isNotBlank() }?.let { json.put("receiver_image_version", it) }
        return json
    }

    /** brand, model, image distro, image version from OpenWebif; null when nothing useful came back. */
    private fun readAbout(target: ReceiverConfig): List<String>? {
        val scheme = if (target.useHttps) "https" else "http"
        val port = target.httpPort.takeIf { it in 1..65535 } ?: if (target.useHttps) 443 else 80
        val host = target.host.trim().let { if (it.contains(':') && !it.startsWith("[")) "[$it]" else it }
        val conn = URL("$scheme://$host:$port/api/about").openConnection() as HttpURLConnection
        val text = try {
            conn.connectTimeout = 6_000
            conn.readTimeout = 8_000
            if (target.username.isNotEmpty()) {
                val token = Base64.encodeToString("${target.username}:${target.password}".toByteArray(), Base64.NO_WRAP)
                conn.setRequestProperty("Authorization", "Basic $token")
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { it.readBytes().take(256 * 1024).toByteArray().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
        val root = JSONObject(text)
        val info = root.optJSONObject("info") ?: root
        fun pick(vararg keys: String): String =
            keys.firstNotNullOfOrNull { k -> info.optString(k, "").trim().takeIf { it.isNotEmpty() && it != "null" } }.orEmpty()
        val values = listOf(
            pick("brand", "manufacturer").take(64),
            pick("model", "boxtype", "friendlyname").take(64),
            pick("friendlyimagedistro", "imagedistro", "distro").take(64),
            pick("imagever", "imageversion", "imagedistroversion").take(32),
        )
        return values.takeIf { v -> v.any { it.isNotBlank() } }
    }

    private fun post(json: JSONObject) {
        val body = json.toString().toByteArray(Charsets.UTF_8)
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
