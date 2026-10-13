package com.enigmacast.domain.account

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountJsonTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun readsTheLoginAnswer() {
        val body = """{"success":true,"message":"OK","data":{"token":"t","token_expires_at":"2026-11-12T08:04:38+01:00",
            "email":"a@b.nl","plan":"premium","is_trial":true,"is_premium":true,"premium_until":"2028-10-12T09:04:38+02:00",
            "max_devices":10,"devices_used":1,"server_time":"2026-10-13T09:04:40+02:00"}}"""
        val envelope = json.decodeFromString<AccountEnvelope>(body)
        val snapshot = envelope.data!!.toSnapshot(nowMs = 5L)
        assertEquals("t", envelope.data!!.token)
        assertEquals(Plan.PREMIUM, snapshot.plan)
        assertTrue(snapshot.serverPremium)
        assertEquals(1_854_947_078_000L, snapshot.premiumUntilMs)
        assertEquals(5L, snapshot.verifiedAtMs)
    }

    @Test
    fun mapsErrorCodes() {
        val envelope = json.decodeFromString<AccountEnvelope>("""{"success":false,"code":"device_limit","message":"Maximum (10)"}""")
        assertFalse(envelope.success)
        assertEquals(AccountError.DEVICE_LIMIT, AccountError.fromCode(envelope.code, 403))
        assertEquals(AccountError.RATE_LIMITED, AccountError.fromCode(null, 429))
        assertEquals(AccountError.SERVER, AccountError.fromCode(null, 502))
        assertTrue(AccountError.DEVICE_REVOKED.endsSession)
        assertFalse(AccountError.NETWORK.endsSession)
    }
}
