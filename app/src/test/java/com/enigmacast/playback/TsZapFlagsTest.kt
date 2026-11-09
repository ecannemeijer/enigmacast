package com.enigmacast.playback

import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TsZapFlagsTest {
    @Test
    fun stableStartsWithNoSpecialFlags() {
        assertEquals(0, TsZapFlags.forLive(fastStart = false))
    }

    @Test
    fun fastStartDetectsAccessUnitsButNeverAllowsNonIdr() {
        val flags = TsZapFlags.forLive(fastStart = true)
        assertTrue(flags and DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS != 0)
        assertEquals(0, flags and DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
    }
}
