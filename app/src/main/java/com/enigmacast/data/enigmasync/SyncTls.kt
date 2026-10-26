package com.enigmacast.data.enigmasync

import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
import okhttp3.TlsVersion
import java.util.concurrent.ConcurrentHashMap

/**
 * TLS settings for the receiver's sync server. ChaCha20-Poly1305 is offered first: the
 * receivers' ARM/MIPS CPUs often have no AES instructions, so ChaCha is the cheapest cipher for
 * them while a long pull response is encrypted. Android cannot narrow the
 * TLS 1.3 suites, so that first offer is TLS 1.2 with only the ChaCha suites (ECDHE, AEAD).
 * When the server refuses it, OkHttp falls back to the normal modern and compatible specs
 * within the same call; a host that did not end up on ChaCha is remembered so later requests
 * skip the extra handshake. Trust (the SPKI pin) is not touched by any of this.
 */
object SyncTls {
    val CHACHA_ONLY: ConnectionSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .tlsVersions(TlsVersion.TLS_1_2)
        .cipherSuites(
            CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
        )
        .build()

    private val chachaWorks = ConcurrentHashMap<String, Boolean>()

    /** ChaCha first for hosts not known to refuse it, then the modern and compatible fallbacks. */
    fun specs(preferChaCha: Boolean): List<ConnectionSpec> =
        if (preferChaCha) listOf(CHACHA_ONLY, ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS)
        else listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS)

    fun isChaCha(cipherSuite: String?): Boolean = cipherSuite?.contains("CHACHA20", ignoreCase = true) == true

    fun prefersChaCha(host: String): Boolean = chachaWorks[host] != false

    /**
     * Records the suite a finished handshake used.
     * @return true when this changed what is known about [host] (worth one log line).
     */
    fun record(host: String, cipherSuite: String?): Boolean {
        if (cipherSuite == null) return false
        val works = isChaCha(cipherSuite)
        return chachaWorks.put(host, works) != works
    }

    /** Forgets what is known about [host] (its sync server was updated or reinstalled). */
    fun forget(host: String) {
        chachaWorks.remove(host)
    }

    internal fun reset() = chachaWorks.clear()
}
