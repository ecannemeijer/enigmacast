package com.enigmacast.data.network

import com.enigmacast.playback.StreamFailures
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OneEnigmaStreamTest {
    @Test
    fun enigmaServicePathsAreTheOnesThatTakeATuner() {
        assertTrue(OneEnigmaStream.isEnigmaService("/1%3A0%3A16%3A1816%3AC82%3A3%3AEB0000%3A0%3A0%3A0%3A"))
        assertTrue(OneEnigmaStream.isEnigmaService("/1:0:1:1:2:3:4:0:0:0:"))
        assertFalse(OneEnigmaStream.isEnigmaService("/live/stream.m3u8"))
        assertFalse(OneEnigmaStream.isEnigmaService("/api/about"))
    }

    @Test
    fun closingTheSocketOnPurposeIsNotAStreamFailure() {
        assertTrue(StreamFailures.isCancellation(IOException("Canceled")))
        assertTrue(StreamFailures.isCancellation(RuntimeException(IOException("Canceled"))))
        assertFalse(StreamFailures.isCancellation(IOException("timeout")))
    }
}
