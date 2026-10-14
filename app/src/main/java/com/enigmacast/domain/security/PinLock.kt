package com.enigmacast.domain.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** A 4-digit PIN kept as "pbkdf2-sha256$<iterations>$<salt>$<hash>" (base64), never as the digits. */
object PinHash {
    const val ITERATIONS = 120_000
    private const val BITS = 256

    fun valid(pin: String) = pin.length == 4 && pin.all { it in '0'..'9' }

    fun create(pin: String, salt: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }, iterations: Int = ITERATIONS): String {
        require(valid(pin))
        val enc = Base64.getEncoder()
        return "pbkdf2-sha256$$iterations$${enc.encodeToString(salt)}$${enc.encodeToString(derive(pin, salt, iterations))}"
    }

    fun verify(pin: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2-sha256" || !valid(pin)) return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val dec = Base64.getDecoder()
        val salt = runCatching { dec.decode(parts[2]) }.getOrNull() ?: return false
        val want = runCatching { dec.decode(parts[3]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(derive(pin, salt, iterations), want)
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, BITS)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
}

/** Wrong PINs: after [MAX_FAILURES] in a row, no attempts for [COOLDOWN_MS]. */
data class PinAttempts(val failures: Int = 0, val lockedUntilMs: Long = 0L) {
    fun coolingDown(nowMs: Long) = nowMs < lockedUntilMs
    fun secondsLeft(nowMs: Long): Int = if (coolingDown(nowMs)) (((lockedUntilMs - nowMs) + 999) / 1000).toInt() else 0
    fun failed(nowMs: Long): PinAttempts =
        if (failures + 1 >= MAX_FAILURES) PinAttempts(0, nowMs + COOLDOWN_MS) else copy(failures = failures + 1)
    fun succeeded() = PinAttempts()
    val left: Int get() = MAX_FAILURES - failures

    companion object {
        const val MAX_FAILURES = 5
        const val COOLDOWN_MS = 30_000L
    }
}

sealed interface PinCheck {
    data object Ok : PinCheck
    data class Wrong(val attemptsLeft: Int) : PinCheck
    data class CoolingDown(val seconds: Int) : PinCheck
}
