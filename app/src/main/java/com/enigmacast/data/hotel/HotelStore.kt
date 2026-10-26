package com.enigmacast.data.hotel

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.enigmacast.data.security.SecretStore
import com.enigmacast.domain.hotel.HotelConfig
import com.enigmacast.domain.hotel.HotelPin
import com.enigmacast.domain.hotel.HotelPinAttempts
import com.enigmacast.domain.hotel.HotelRuntime
import com.enigmacast.domain.hotel.LauncherToggle
import com.enigmacast.domain.security.PinCheck
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Hotel mode's settings on this TV only: a small private preferences file (the app has
 * allowBackup="false", so it is never restored on another device) and never part of the sync.
 * The hotel PIN (salted PBKDF2 hash) and the guests' Wi-Fi password live in the encrypted
 * [SecretStore] and are only read off the main thread. Nothing here is logged.
 */
@Singleton
class HotelStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secrets: SecretStore,
) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val _config = MutableStateFlow(load())
    val config: StateFlow<HotelConfig> = _config.asStateFlow()
    private val _pinSet = MutableStateFlow(prefs.getBoolean(PIN_SET, false))
    /** A hotel PIN exists (kept as a flag, so nobody has to decrypt to know it). */
    val pinSet: StateFlow<Boolean> = _pinSet.asStateFlow()
    private val _unlocked = MutableStateFlow(false)
    /** The owner typed the hotel PIN; ends when the hotel settings close or the app goes to the background. */
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    init {
        HotelRuntime.set(_config.value)
        // The HOME alias follows the stored state (also repairs it after an update or a crash).
        scope.launch { applyLauncher(_config.value) }
    }

    val current: HotelConfig get() = _config.value

    private fun load(): HotelConfig {
        val raw = prefs.getString(KEY, null) ?: return HotelConfig()
        val parsed = runCatching { json.decodeFromString(HotelConfig.serializer(), raw) }.getOrNull() ?: HotelConfig()
        // Hotel mode never starts without a PIN: without one the owner could not get out.
        return if (parsed.enabled && !prefs.getBoolean(PIN_SET, false)) parsed.copy(enabled = false) else parsed
    }

    fun update(transform: (HotelConfig) -> HotelConfig): HotelConfig {
        val next: HotelConfig
        synchronized(lock) {
            val before = _config.value
            next = transform(before).let { if (it.enabled && !_pinSet.value) it.copy(enabled = false) else it }
            if (next == before) return next
            prefs.edit().putString(KEY, json.encodeToString(HotelConfig.serializer(), next)).apply()
            _config.value = next
            HotelRuntime.set(next)
        }
        scope.launch { applyLauncher(next) }
        return next
    }

    /** Checks the hotel PIN; wrong tries and lockouts are kept across restarts. */
    suspend fun check(pin: String, nowMs: Long = System.currentTimeMillis()): PinCheck = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val attempts = prefs.getString(ATTEMPTS, null)
                ?.let { runCatching { json.decodeFromString(HotelPinAttempts.serializer(), it) }.getOrNull() }
                ?: HotelPinAttempts()
            val stored = if (_pinSet.value) secrets.read(PIN) else null
            val (result, next) = if (_pinSet.value && stored == null) {
                // The key is gone (data restored elsewhere): the flag lies, nothing to check against.
                PinCheck.Ok to attempts
            } else attempts.check(pin, stored, nowMs)
            if (next != attempts) prefs.edit().putString(ATTEMPTS, json.encodeToString(HotelPinAttempts.serializer(), next)).apply()
            result
        }
    }

    suspend fun unlock(pin: String): PinCheck = check(pin).also { if (it == PinCheck.Ok) _unlocked.value = true }

    /** The owner opened the hotel settings from the normal (non-hotel) Settings: no PIN needed there. */
    fun unlockForOwner() { _unlocked.value = true }

    fun relock() { _unlocked.value = false }

    suspend fun setPin(pin: String): Boolean = withContext(Dispatchers.IO) {
        if (!HotelPin.valid(pin)) return@withContext false
        secrets.write(PIN, HotelPin.hash(pin))
        prefs.edit().putBoolean(PIN_SET, true).remove(ATTEMPTS).apply()
        _pinSet.value = true
        applyLauncher(_config.value)
        true
    }

    /** Only while hotel mode is off (otherwise the owner could lock themselves out). */
    suspend fun clearPin(): Boolean = withContext(Dispatchers.IO) {
        if (_config.value.enabled) return@withContext false
        secrets.write(PIN, null)
        prefs.edit().putBoolean(PIN_SET, false).remove(ATTEMPTS).apply()
        _pinSet.value = false
        applyLauncher(_config.value)
        true
    }

    suspend fun wifiPassword(): String = withContext(Dispatchers.IO) { secrets.read(WIFI).orEmpty() }

    suspend fun setWifiPassword(value: String) = withContext(Dispatchers.IO) { secrets.write(WIFI, value.ifEmpty { null }) }

    /** The HOME alias ("EnigmaCast as launcher") is enabled only per [LauncherToggle]. */
    private fun applyLauncher(config: HotelConfig) {
        val wanted = LauncherToggle.aliasEnabled(config, _pinSet.value)
        val component = ComponentName(context.packageName, LAUNCHER_ALIAS)
        runCatching {
            val pm = context.packageManager
            val state = if (wanted) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (pm.getComponentEnabledSetting(component) != state) {
                pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            }
        }.onFailure { Timber.w("Hotel launcher alias: %s", it.javaClass.simpleName) }
    }

    /** True when this TV currently opens EnigmaCast on HOME (the owner picked it as default launcher). */
    suspend fun isDefaultLauncher(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == context.packageName
        }.getOrDefault(false)
    }

    companion object {
        const val FILE = "hotel_mode"
        private const val KEY = "config"
        private const val PIN_SET = "pin_set"
        private const val ATTEMPTS = "pin_attempts"
        private const val PIN = "hotel_pin"
        private const val WIFI = "hotel_wifi_password"
        const val LAUNCHER_ALIAS = "com.enigmacast.HotelLauncher"
    }
}
