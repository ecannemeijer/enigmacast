package com.enigmacast.ui.xtream

import org.junit.Assert.assertEquals
import org.junit.Test

class LogoDecodeTest {
    @Test
    fun largeLogosAreSubsampledWhileDecoding() {
        assertEquals(1, LogoDecode.sampleSize(150, 192))
        assertEquals(1, LogoDecode.sampleSize(300, 192))
        assertEquals(4, LogoDecode.sampleSize(1000, 192))
        assertEquals(8, LogoDecode.sampleSize(2048, 192))
    }
}
