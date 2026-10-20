package com.enigmacast.domain.profile.photo

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The single-use secret in the upload URL. Valid for [ttlMs] or until [consume]. Compared in
 * constant time. Never log [value]; [toString] hides it.
 */
class UploadToken(val value: String, private val createdAt: Long, private val ttlMs: Long = TTL_MS) {
    @Volatile private var used = false

    fun expiresAt(): Long = createdAt + ttlMs
    fun expired(now: Long): Boolean = now >= expiresAt()
    val consumed: Boolean get() = used

    /** True when [candidate] is this token, it has not expired and was not used yet. */
    fun accepts(candidate: String?, now: Long): Boolean {
        if (candidate == null || used || expired(now)) return false
        return MessageDigest.isEqual(candidate.toByteArray(Charsets.UTF_8), value.toByteArray(Charsets.UTF_8))
    }

    /** One successful upload: the token is spent. */
    fun consume() { used = true }

    override fun toString(): String = "UploadToken(****, expires=${expiresAt()}, used=$used)"

    companion object {
        const val TTL_MS = 10 * 60_000L
        private val random = SecureRandom()

        /** 128 random bits, URL-safe base64 without padding (22 characters). */
        fun create(now: Long, ttlMs: Long = TTL_MS): UploadToken {
            val bytes = ByteArray(16).also { random.nextBytes(it) }
            return UploadToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), now, ttlMs)
        }

        /** Replaces token=… in a path or URL, for logs. */
        fun redact(text: String): String = text.replace(Regex("(token=)[^&\\s]*"), "$1****")
    }
}
