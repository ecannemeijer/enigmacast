package com.enigmacast.companionbridge

import android.util.Base64
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

class CompanionAuthException(val code: String, message: String) : Exception(message)

/**
 * Verifieert de getekende requests. Exact het schema van EnigmaCast's syncserver:
 * `timestamp \n nonce \n METHOD \n path?query \n SHA-256(body)`.
 */
@Singleton
class CompanionAuth @Inject constructor(
    private val devices: CompanionDevices,
) {
    private val nonces = ConcurrentHashMap<String, Long>()

    fun authenticate(
        headers: Map<String, String>,
        method: String,
        pathWithQuery: String,
        body: ByteArray,
    ): CompanionDevice {
        val authorization = headers["authorization"].orEmpty()
        if (!authorization.startsWith("Bearer ", ignoreCase = true)) {
            throw CompanionAuthException(CompanionProtocol.ERR_AUTH_MISSING, "missing token")
        }
        val token = authorization.substring(7).trim()
        val device = devices.findByToken(token)
            ?: throw CompanionAuthException(CompanionProtocol.ERR_AUTH_SIGNATURE, "unknown device")
        if (device.revoked) throw CompanionAuthException(CompanionProtocol.ERR_DEVICE_REVOKED, "device revoked")

        val stamp = headers["x-timestamp"] ?: throw CompanionAuthException(CompanionProtocol.ERR_AUTH_MISSING, "missing timestamp")
        val nonce = headers["x-nonce"] ?: throw CompanionAuthException(CompanionProtocol.ERR_AUTH_MISSING, "missing nonce")
        val signature = headers["x-signature"] ?: throw CompanionAuthException(CompanionProtocol.ERR_AUTH_MISSING, "missing signature")
        val timestamp = stamp.toLongOrNull()
            ?: throw CompanionAuthException(CompanionProtocol.ERR_AUTH_SKEW, "bad timestamp")
        val now = System.currentTimeMillis()
        if (kotlin.math.abs(now - timestamp) > CompanionProtocol.AUTH_SKEW_MS) {
            throw CompanionAuthException(CompanionProtocol.ERR_AUTH_SKEW, "timestamp out of range")
        }
        cleanup(now)
        if (nonces.putIfAbsent(nonce, now + CompanionProtocol.AUTH_SKEW_MS) != null) {
            throw CompanionAuthException(CompanionProtocol.ERR_AUTH_REPLAY, "nonce already used")
        }

        val canonical = canonical(timestamp.toString(), nonce, method, pathWithQuery, body)
        val valid = runCatching {
            val keyBytes = Base64.decode(device.publicKey, Base64.DEFAULT)
            val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(canonical)
            verifier.verify(Base64.decode(signature, Base64.DEFAULT))
        }.getOrDefault(false)
        if (!valid) {
            nonces.remove(nonce)
            throw CompanionAuthException(CompanionProtocol.ERR_AUTH_SIGNATURE, "bad signature")
        }
        return device
    }

    companion object {
        /** Canonieke bytes die beide kanten ondertekenen. */
        fun canonical(timestamp: String, nonce: String, method: String, path: String, body: ByteArray): ByteArray {
            val head = "$timestamp\n$nonce\n${method.uppercase()}\n$path\n".toByteArray(Charsets.UTF_8)
            val hash = MessageDigest.getInstance("SHA-256").digest(body)
            return head + hash
        }
    }

    private fun cleanup(now: Long) {
        if (nonces.size < 256) return
        nonces.entries.removeIf { it.value < now }
    }
}
