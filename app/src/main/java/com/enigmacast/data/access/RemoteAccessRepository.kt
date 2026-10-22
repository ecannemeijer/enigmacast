package com.enigmacast.data.access

import android.content.Context
import com.enigmacast.BuildConfig
import com.enigmacast.data.account.AccountRepository
import com.enigmacast.domain.access.AccessConfig
import com.enigmacast.domain.access.AccessMode
import com.enigmacast.util.EnigmaLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
private data class AccessEnvelope(
    val success: Boolean = false,
    val data: AccessDto? = null,
)

@Serializable
private data class AccessDto(
    val mode: String = "free",
    @SerialName("authEpoch") val authEpoch: Long = 0,
    @SerialName("minVersionCode") val minVersionCode: Int = 0,
    @SerialName("minVersionName") val minVersionName: String = "",
    @SerialName("updateUrl") val updateUrl: String = "",
    val message: String = "",
    val features: Map<String, Boolean> = emptyMap(),
)

/**
 * Reads the Free/Premium switch from the account API and caches it. A fresh install starts Free
 * (the app is free until the server says otherwise); a cached Premium mode is kept while offline.
 */
@Singleton
class RemoteAccessRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val account: AccountRepository,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    private val baseUrl = BuildConfig.ACCOUNT_API_URL.trimEnd('/')
    private val _config = MutableStateFlow(readCache())
    val config: StateFlow<AccessConfig> = _config.asStateFlow()

    fun current(): AccessConfig = _config.value

    suspend fun refresh(): AccessConfig = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/enigmacast/config")
            .header("Accept", "application/json")
            .get()
            .build()
        val remote = runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body.string()
                val envelope = json.decodeFromString<AccessEnvelope>(body)
                if (!envelope.success) error("config rejected")
                envelope.data ?: error("config missing")
            }
        }.getOrElse { error ->
            EnigmaLog.connection("access config unavailable: ${error.javaClass.simpleName}")
            return@withContext _config.value
        }
        val mode = if (remote.mode.equals("premium", ignoreCase = true)) AccessMode.PREMIUM else AccessMode.FREE
        val previousEpoch = prefs.getLong(EPOCH, -1L)
        val cached = AccessConfig(
            mode = mode,
            authEpoch = remote.authEpoch.coerceAtLeast(0),
            minVersionCode = remote.minVersionCode.coerceAtLeast(0),
            minVersionName = remote.minVersionName,
            updateUrl = remote.updateUrl.ifBlank { DEFAULT_UPDATE_URL },
            message = remote.message,
            features = remote.features,
            verifiedAtMs = System.currentTimeMillis(),
        )
        // Free -> Premium (or a forced re-login) clears the old session before the gate is shown.
        if (mode == AccessMode.PREMIUM && previousEpoch >= 0 && cached.authEpoch > previousEpoch) {
            EnigmaLog.service("access epoch ${previousEpoch} -> ${cached.authEpoch}: forcing login")
            account.forceSignOut()
        }
        save(cached)
        _config.value = cached
        cached
    }

    private fun save(config: AccessConfig) {
        prefs.edit()
            .putString(MODE, config.mode.name)
            .putLong(EPOCH, config.authEpoch)
            .putInt(MIN_CODE, config.minVersionCode)
            .putString(MIN_NAME, config.minVersionName)
            .putString(UPDATE_URL, config.updateUrl)
            .putString(MESSAGE, config.message)
            .putString(FEATURES, config.features.entries.joinToString(";") { "${it.key}=${it.value}" })
            .putLong(VERIFIED, config.verifiedAtMs)
            .apply()
    }

    private fun readCache(): AccessConfig {
        val mode = runCatching { AccessMode.valueOf(prefs.getString(MODE, null).orEmpty()) }
            .getOrDefault(AccessMode.FREE)
        val features = prefs.getString(FEATURES, null).orEmpty().split(';').mapNotNull { item ->
            val key = item.substringBefore('=', "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            key to item.substringAfter('=', "false").toBoolean()
        }.toMap()
        return AccessConfig(
            mode = mode,
            authEpoch = prefs.getLong(EPOCH, 0L),
            minVersionCode = prefs.getInt(MIN_CODE, 0),
            minVersionName = prefs.getString(MIN_NAME, "").orEmpty(),
            updateUrl = prefs.getString(UPDATE_URL, DEFAULT_UPDATE_URL).orEmpty().ifBlank { DEFAULT_UPDATE_URL },
            message = prefs.getString(MESSAGE, "").orEmpty(),
            features = features,
            verifiedAtMs = prefs.getLong(VERIFIED, 0L),
        )
    }

    private companion object {
        const val PREFS = "enigmacast_access"
        const val MODE = "mode"
        const val EPOCH = "auth_epoch"
        const val MIN_CODE = "min_version_code"
        const val MIN_NAME = "min_version_name"
        const val UPDATE_URL = "update_url"
        const val MESSAGE = "message"
        const val FEATURES = "features"
        const val VERIFIED = "verified_at"
        const val DEFAULT_UPDATE_URL = "https://update.velixatv.com/enigmacast.apk"
    }
}
