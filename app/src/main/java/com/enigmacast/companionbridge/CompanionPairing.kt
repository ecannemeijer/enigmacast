package com.enigmacast.companionbridge

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/** De eenmalige koppelcode. Alleen op de TV zichtbaar; nooit gelogd. */
class CompanionPairing {
    private val random = SecureRandom()
    private val lock = Any()
    private var code: String? = null
    private var expiresAt: Long = 0L

    fun newCode(now: Long = System.currentTimeMillis()): String = synchronized(lock) {
        val fresh = String.format(Locale.US, "%06d", random.nextInt(1_000_000))
        code = fresh
        expiresAt = now + CompanionProtocol.PAIR_CODE_TTL_MS
        fresh
    }

    fun current(now: Long = System.currentTimeMillis()): String? = synchronized(lock) {
        val value = code
        if (value == null || now >= expiresAt) {
            code = null
            null
        } else {
            value
        }
    }

    fun expiresAt(): Long = synchronized(lock) { expiresAt }

    fun consume(candidate: String, now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        val value = code ?: return false
        if (now >= expiresAt) {
            code = null
            return false
        }
        if (!MessageDigest.isEqual(value.toByteArray(Charsets.UTF_8), candidate.toByteArray(Charsets.UTF_8))) {
            return false
        }
        code = null
        true
    }

    fun clear() = synchronized(lock) {
        code = null
        expiresAt = 0
    }
}
