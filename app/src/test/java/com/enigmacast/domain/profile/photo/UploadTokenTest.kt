package com.enigmacast.domain.profile.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadTokenTest {
    @Test fun acceptsOnlyItsOwnValueUntilExpiry() {
        val t = UploadToken.create(now = 1_000, ttlMs = 60_000)
        assertTrue(t.accepts(t.value, 1_000))
        assertTrue(t.accepts(t.value, 60_999))
        assertFalse(t.accepts(t.value, 61_000))
        assertFalse(t.accepts(t.value.dropLast(1) + "x", 2_000))
        assertFalse(t.accepts(null, 2_000))
        assertFalse(t.accepts("", 2_000))
    }

    @Test fun isSingleUse() {
        val t = UploadToken.create(0)
        t.consume()
        assertFalse(t.accepts(t.value, 1))
    }

    @Test fun isRandomUrlSafeAndNeverPrinted() {
        val a = UploadToken.create(0)
        val b = UploadToken.create(0)
        assertNotEquals(a.value, b.value)
        assertEquals(22, a.value.length)
        assertTrue(a.value.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertFalse(a.toString().contains(a.value))
        assertEquals("GET /upload?token=****&x=1", UploadToken.redact("GET /upload?token=${a.value}&x=1"))
        assertEquals(10 * 60_000L, UploadToken.create(0).expiresAt())
    }
}
