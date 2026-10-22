package com.enigmacast.companionbridge

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM-tests voor de protocolbouwstenen van de TV-brug (geen Android nodig). */
class CompanionBridgeTest {

    @Test
    fun websocketAcceptKeyMatchesTheRfcExample() {
        // RFC 6455, sectie 1.3.
        assertEquals(
            "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
            CompanionWebSocket.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="),
        )
    }

    @Test
    fun pairingCodeIsSingleUse() {
        val pairing = CompanionPairing()
        val code = pairing.newCode(now = 1_000)
        assertTrue(pairing.consume(code, now = 1_001))
        assertFalse(pairing.consume(code, now = 1_002))
    }

    @Test
    fun pairingCodeExpires() {
        val pairing = CompanionPairing()
        val code = pairing.newCode(now = 1_000)
        assertFalse(pairing.consume(code, now = 1_000 + CompanionProtocol.PAIR_CODE_TTL_MS + 1))
        assertEquals(null, pairing.current(now = 1_000 + CompanionProtocol.PAIR_CODE_TTL_MS + 1))
    }

    @Test
    fun envelopeRoundTrips() {
        val envelope = CompanionProtocol.envelope(
            CompanionProtocol.TYPE_ACK,
            CompanionProtocol.ackPayload(12),
            id = "c-1",
        )
        val decoded = CompanionProtocol.decode(CompanionProtocol.encode(envelope))
        assertEquals(CompanionProtocol.TYPE_ACK, decoded?.type)
        assertEquals("c-1", decoded?.id)
        assertTrue(CompanionProtocol.encode(envelope).contains("\"ok\":true"))
    }

    @Test
    fun canonicalSignatureIsStable() {
        val canonical = CompanionAuth.canonical(
            timestamp = "1761100000123",
            nonce = "5b0e2f5e-6a1f-4f0e-9b1a-2f6f0f2a9c11",
            method = "get",
            path = "/api/v1/state?since=41",
            body = "{}".toByteArray(),
        )
        val head = "1761100000123\n5b0e2f5e-6a1f-4f0e-9b1a-2f6f0f2a9c11\nGET\n/api/v1/state?since=41\n"
            .toByteArray(Charsets.UTF_8)
        assertEquals(head.size + 32, canonical.size)
        assertEquals(
            "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a",
            MessageDigest.getInstance("SHA-256").digest("{}".toByteArray()).joinToString("") { "%02x".format(it) },
        )
        assertNotEquals(0, canonical.size)
    }

    @Test
    fun formCapabilityAndTypesMatchTheCompanion() {
        assertTrue(CompanionProtocol.CAPABILITIES.contains("text.form"))
        assertEquals("form", CompanionProtocol.TYPE_FORM)
        assertEquals("cmd.form", CompanionProtocol.TYPE_CMD_FORM)
    }
}
