package com.enigmacast.data.enigmasync

import okhttp3.ConnectionSpec
import okhttp3.TlsVersion
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncTlsTest {
    @After
    fun clear() = SyncTls.reset()

    @Test
    fun chachaFirstThenTheNormalFallbacks() {
        val specs = SyncTls.specs(preferChaCha = true)
        assertEquals(listOf(SyncTls.CHACHA_ONLY, ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS), specs)
        assertEquals(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS), SyncTls.specs(preferChaCha = false))
    }

    @Test
    fun chachaSpecOnlyOffersChachaOnTls12() {
        val suites = SyncTls.CHACHA_ONLY.cipherSuites!!.map { it.javaName }
        assertTrue(suites.isNotEmpty())
        assertTrue(suites.all { it.contains("CHACHA20_POLY1305") && it.contains("ECDHE") })
        assertEquals(listOf(TlsVersion.TLS_1_2), SyncTls.CHACHA_ONLY.tlsVersions)
    }

    @Test
    fun hostThatRefusesChachaSkipsItLater() {
        assertTrue(SyncTls.prefersChaCha("box"))
        assertTrue(SyncTls.record("box", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"))
        assertFalse(SyncTls.prefersChaCha("box"))
        assertFalse(SyncTls.record("box", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"))
        assertTrue(SyncTls.prefersChaCha("other"))
    }

    @Test
    fun chachaHostIsLoggedOnce() {
        assertTrue(SyncTls.record("box", "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"))
        assertFalse(SyncTls.record("box", "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"))
        assertTrue(SyncTls.prefersChaCha("box"))
        assertFalse(SyncTls.record("box", null))
        assertTrue(SyncTls.isChaCha("TLS_CHACHA20_POLY1305_SHA256"))
        assertFalse(SyncTls.isChaCha(null))
    }
}
