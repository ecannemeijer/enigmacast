package com.enigmacast.data.telemetry

import android.content.Context
import android.os.Build
import com.enigmacast.BuildConfig
import com.enigmacast.util.EnigmaLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Anonymous usage counter. One random install id, one heartbeat per day; no account, e-mail,
 * content or IP is sent by the app. Enabled by default.
 */
@Singleton
class TelemetryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    private val baseUrl = BuildConfig.ACCOUNT_API_URL.trimEnd('/')
    @Volatile private var started = false

    fun start() {
        if (started || !prefs.getBoolean(ENABLED, true)) return
        started = true
        val now = System.currentTimeMillis()
        val last = prefs.getLong(LAST_SENT, 0L)
        if (last > 0L && now - last < DAY_MS) return
        val installId = prefs.getString(INSTALL_ID, null)?.takeIf { it.length >= 8 }
            ?: ("ec-" + UUID.randomUUID().toString()).also {
                prefs.edit().putString(INSTALL_ID, it).apply()
            }
        val payload = buildJsonObject {
            put("install_id", installId)
            put("platform", "androidtv")
            put("app_version", BuildConfig.VERSION_NAME)
            put("version_code", BuildConfig.VERSION_CODE)
            put("os_version", "Android ${Build.VERSION.RELEASE}")
        }.toString()
        scope.launch {
            val request = Request.Builder()
                .url("$baseUrl/api/enigmacast/telemetry")
                .post(payload.toRequestBody(JSON))
                .build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        prefs.edit().putLong(LAST_SENT, System.currentTimeMillis()).apply()
                    } else {
                        EnigmaLog.connection("telemetry HTTP ${response.code}")
                    }
                }
            }.onFailure {
                EnigmaLog.connection("telemetry unavailable: ${it.javaClass.simpleName}")
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(ENABLED, enabled).apply()
    }

    private companion object {
        const val PREFS = "enigmacast_telemetry"
        const val ENABLED = "enabled"
        const val INSTALL_ID = "install_id"
        const val LAST_SENT = "last_sent"
        const val DAY_MS = 24L * 60 * 60 * 1000
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
