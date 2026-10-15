package com.enigmacast.data.security

import com.enigmacast.domain.security.PinAttempts
import com.enigmacast.domain.security.PinCheck
import com.enigmacast.domain.security.PinHash
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional per-profile PIN. The salted hash lives in [SecretStore] under `profile_pin:{uuid}`.
 * The digits are never stored or logged. Too many misses cool down, same as the settings lock.
 */
@Singleton
class ProfilePinLock @Inject constructor(private val secrets: SecretStore) {
    private val attempts = HashMap<String, PinAttempts>()

    fun hasPin(uuid: String): Boolean = secrets.read(key(uuid)) != null

    @Synchronized
    fun check(uuid: String, pin: String, nowMs: Long = System.currentTimeMillis()): PinCheck {
        val current = attempts[uuid] ?: PinAttempts()
        if (current.coolingDown(nowMs)) return PinCheck.CoolingDown(current.secondsLeft(nowMs))
        val stored = secrets.read(key(uuid)) ?: return PinCheck.Ok
        return if (PinHash.verify(pin, stored)) {
            attempts[uuid] = current.succeeded()
            PinCheck.Ok
        } else {
            val next = current.failed(nowMs)
            attempts[uuid] = next
            if (next.coolingDown(nowMs)) PinCheck.CoolingDown(next.secondsLeft(nowMs)) else PinCheck.Wrong(next.left)
        }
    }

    fun setPin(uuid: String, pin: String) {
        secrets.write(key(uuid), PinHash.create(pin))
        synchronized(this) { attempts.remove(uuid) }
    }

    fun clear(uuid: String) {
        secrets.write(key(uuid), null)
        synchronized(this) { attempts.remove(uuid) }
    }

    private fun key(uuid: String) = "profile_pin:$uuid"
}
