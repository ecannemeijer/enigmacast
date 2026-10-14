package com.enigmacast.data.security

import com.enigmacast.domain.security.PinAttempts
import com.enigmacast.domain.security.PinCheck
import com.enigmacast.domain.security.PinHash
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings lock: the PIN's salted PBKDF2 hash in the encrypted [SecretStore] (never the digits,
 * never logged). Unlocked until Settings is left or the app goes to the background.
 * Forgotten PIN: clearing the app's data removes it (there is no other way in).
 */
@Singleton
class SettingsLock @Inject constructor(private val secrets: SecretStore) {
    private val _enabled = MutableStateFlow(secrets.read(KEY) != null)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()
    private var attempts = PinAttempts()

    @Synchronized
    fun check(pin: String, nowMs: Long = System.currentTimeMillis()): PinCheck {
        if (attempts.coolingDown(nowMs)) return PinCheck.CoolingDown(attempts.secondsLeft(nowMs))
        val stored = secrets.read(KEY) ?: return PinCheck.Ok
        return if (PinHash.verify(pin, stored)) {
            attempts = attempts.succeeded()
            PinCheck.Ok
        } else {
            attempts = attempts.failed(nowMs)
            if (attempts.coolingDown(nowMs)) PinCheck.CoolingDown(attempts.secondsLeft(nowMs)) else PinCheck.Wrong(attempts.left)
        }
    }

    fun unlock(pin: String): PinCheck = check(pin).also { if (it == PinCheck.Ok) _unlocked.value = true }

    fun relock() { _unlocked.value = false }

    fun setPin(pin: String) {
        secrets.write(KEY, PinHash.create(pin))
        _enabled.value = true
        _unlocked.value = true
    }

    /** Needs the current PIN. */
    fun disable(pin: String): PinCheck = check(pin).also {
        if (it == PinCheck.Ok) { secrets.write(KEY, null); _enabled.value = false }
    }

    private companion object { const val KEY = "settings_lock_pin" }
}
