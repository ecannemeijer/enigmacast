package com.enigmacast.data.account

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.enigmacast.BuildConfig
import com.enigmacast.data.security.SecretStore
import com.enigmacast.domain.account.AccountData
import com.enigmacast.domain.account.AccountEnvelope
import com.enigmacast.domain.account.AccountError
import com.enigmacast.domain.account.AccountInput
import com.enigmacast.domain.account.AccountSnapshot
import com.enigmacast.domain.account.Entitlements
import com.enigmacast.domain.account.Plan
import com.enigmacast.domain.account.toSnapshot
import com.enigmacast.util.EnigmaLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface AccountResult {
    data class Success(val account: AccountSnapshot) : AccountResult
    data class Failure(val error: AccountError, val maxDevices: Int? = null) : AccountResult
}

/**
 * The EnigmaCast account (register, log in, status, log out) against the VelixaTV API server.
 * The session token sits in the Keystore-backed [SecretStore]; the last server answer (plan,
 * premium_until, when it was checked) is cached so the plan holds offline for a grace period.
 */
@Singleton
class AccountRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secrets: SecretStore,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val mutex = Mutex()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val baseUrl = BuildConfig.ACCOUNT_API_URL.trimEnd('/')

    private val _account = MutableStateFlow(readCache())
    /** Null when nobody is signed in on this device. */
    val account: StateFlow<AccountSnapshot?> = _account.asStateFlow()

    fun isSignedIn(): Boolean = _account.value != null && secrets.read(TOKEN) != null

    /** A random id for this installation, sent with login so the server can count devices. */
    val deviceId: String
        get() = prefs.getString(DEVICE_ID, null) ?: ("ec-" + UUID.randomUUID().toString()).also {
            prefs.edit().putString(DEVICE_ID, it).apply()
        }

    suspend fun register(email: String, password: String): AccountResult = signIn("register", email, password)

    suspend fun login(email: String, password: String): AccountResult = signIn("login", email, password)

    /**
     * Asks the server for the current plan. A dead session (revoked device, new password, blocked
     * account) signs out; no network keeps the cached plan, which [Entitlements] honours for the grace period.
     */
    suspend fun refresh(): AccountResult? = mutex.withLock {
        val token = secrets.read(TOKEN) ?: return@withLock null
        val result = call(Request.Builder().url("$baseUrl/api/enigmacast/me").header("Authorization", "Bearer $token").get().build())
        if (result is AccountResult.Failure && result.error.endsSession) {
            EnigmaLog.service("account session ended: ${result.error}")
            clear()
        }
        result
    }

    /** Refreshes when the cached answer is older than [Entitlements.REFRESH_INTERVAL_MS]. */
    suspend fun refreshIfDue(nowMs: Long = System.currentTimeMillis()) {
        if (Entitlements.needsRefresh(_account.value, nowMs)) refresh()
    }

    /** Unregisters this device on the server (best effort) and forgets the session here. */
    suspend fun logout() = mutex.withLock {
        val token = secrets.read(TOKEN)
        if (token != null) {
            runCatching {
                call(
                    Request.Builder().url("$baseUrl/api/enigmacast/logout")
                        .header("Authorization", "Bearer $token")
                        .post(ByteArray(0).toRequestBody(null))
                        .build(),
                    store = false,
                )
            }
        }
        clear()
    }

    private suspend fun signIn(path: String, email: String, password: String): AccountResult = mutex.withLock {
        val body = buildJsonObject {
            put("email", AccountInput.normalizeEmail(email))
            put("password", password)
            put("device_id", deviceId)
            put("device_name", deviceName())
            put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            put("app_version", BuildConfig.VERSION_NAME)
        }.toString()
        call(
            Request.Builder().url("$baseUrl/api/enigmacast/$path")
                .post(body.toRequestBody(JSON))
                .build(),
        )
    }

    private suspend fun call(request: Request, store: Boolean = true): AccountResult = withContext(Dispatchers.IO) {
        val response = runCatching { client.newCall(request).execute() }.getOrElse { error ->
            EnigmaLog.connection("account call failed: ${error.javaClass.simpleName}")
            return@withContext AccountResult.Failure(AccountError.NETWORK)
        }
        response.use {
            val text = it.body.string()
            val envelope = runCatching { json.decodeFromString<AccountEnvelope>(text) }.getOrNull()
            if (!it.isSuccessful || envelope?.success != true) {
                val error = AccountError.fromCode(envelope?.code, it.code)
                val max = if (error == AccountError.DEVICE_LIMIT) Regex("\\d+").find(envelope?.message.orEmpty())?.value?.toIntOrNull() else null
                return@withContext AccountResult.Failure(error, max)
            }
            val data = envelope.data ?: return@withContext AccountResult.Failure(AccountError.SERVER)
            if (!store) return@withContext AccountResult.Success(_account.value ?: data.toSnapshot(System.currentTimeMillis()))
            AccountResult.Success(save(data))
        }
    }

    private fun save(data: AccountData): AccountSnapshot {
        val snapshot = data.toSnapshot(System.currentTimeMillis())
        data.token?.let { secrets.write(TOKEN, it) }
        prefs.edit()
            .putString(EMAIL, snapshot.email)
            .putString(PLAN, snapshot.plan.name)
            .putBoolean(PREMIUM, snapshot.serverPremium)
            .putLong(UNTIL, snapshot.premiumUntilMs ?: NONE)
            .putBoolean(TRIAL, snapshot.isTrial)
            .putLong(VERIFIED, snapshot.verifiedAtMs)
            .putInt(MAX_DEVICES, snapshot.maxDevices)
            .putInt(DEVICES_USED, snapshot.devicesUsed)
            .apply()
        _account.value = snapshot
        return snapshot
    }

    private fun clear() {
        secrets.write(TOKEN, null)
        prefs.edit().remove(EMAIL).remove(PLAN).remove(PREMIUM).remove(UNTIL).remove(TRIAL).remove(VERIFIED)
            .remove(MAX_DEVICES).remove(DEVICES_USED).apply()
        _account.value = null
    }

    private fun readCache(): AccountSnapshot? {
        val email = prefs.getString(EMAIL, null) ?: return null
        val until = prefs.getLong(UNTIL, NONE)
        return AccountSnapshot(
            email = email,
            plan = runCatching { Plan.valueOf(prefs.getString(PLAN, null).orEmpty()) }.getOrDefault(Plan.FREE),
            serverPremium = prefs.getBoolean(PREMIUM, false),
            premiumUntilMs = until.takeIf { it != NONE },
            isTrial = prefs.getBoolean(TRIAL, false),
            verifiedAtMs = prefs.getLong(VERIFIED, 0L),
            maxDevices = prefs.getInt(MAX_DEVICES, 10),
            devicesUsed = prefs.getInt(DEVICES_USED, 1),
        )
    }

    private fun deviceName(): String =
        runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL.orEmpty()

    private companion object {
        const val PREFS = "enigmacast_account"
        const val TOKEN = "account_token"
        const val DEVICE_ID = "device_id"
        const val EMAIL = "email"
        const val PLAN = "plan"
        const val PREMIUM = "server_premium"
        const val UNTIL = "premium_until"
        const val TRIAL = "trial"
        const val VERIFIED = "verified_at"
        const val MAX_DEVICES = "max_devices"
        const val DEVICES_USED = "devices_used"
        const val NONE = -1L
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
