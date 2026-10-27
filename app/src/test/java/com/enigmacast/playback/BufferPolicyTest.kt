package com.enigmacast.playback

import com.enigmacast.playback.BufferPolicy.MB
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferPolicyTest {
    @Test
    fun aQuarterOfTheHeapForFilmsWithinBounds() {
        assertEquals(64 * MB, BufferPolicy.targetBytes(256L * MB, BufferPolicy.Kind.VOD))
        assertEquals(128 * MB, BufferPolicy.targetBytes(512L * MB, BufferPolicy.Kind.VOD))
        assertEquals(32 * MB, BufferPolicy.targetBytes(96L * MB, BufferPolicy.Kind.VOD))
        assertEquals(160 * MB, BufferPolicy.targetBytes(2048L * MB, BufferPolicy.Kind.VOD))
    }

    @Test
    fun liveKeepsLessAndNeverFillsTheHeap() {
        assertEquals(16 * MB, BufferPolicy.targetBytes(128L * MB, BufferPolicy.Kind.LIVE))
        assertEquals(64 * MB, BufferPolicy.targetBytes(1024L * MB, BufferPolicy.Kind.LIVE))
        for (heap in listOf(128L, 192L, 256L, 384L, 512L)) {
            val bytes = heap * MB
            assertTrue(BufferPolicy.targetBytes(bytes, BufferPolicy.Kind.LIVE) + BufferPolicy.targetBytes(bytes, BufferPolicy.Kind.VOD) <= bytes / 2)
        }
    }

    @Test
    fun anUnknownHeapUsesASafeDefault() {
        assertEquals(64 * MB, BufferPolicy.targetBytes(Long.MAX_VALUE, BufferPolicy.Kind.VOD))
        assertEquals(64 * MB, BufferPolicy.targetBytes(0, BufferPolicy.Kind.VOD))
    }

    @Test
    fun starvedOnlyAfterTheWholeWait() {
        val s = StarvedBuffer(5_000)
        assertFalse(s.update(buffering = true, loading = false, bufferedMs = 100, nowMs = 0))
        assertFalse(s.update(buffering = true, loading = false, bufferedMs = 100, nowMs = 4_999))
        assertTrue(s.update(buffering = true, loading = false, bufferedMs = 100, nowMs = 5_000))
        // Fired once; the next answer needs another full wait.
        assertFalse(s.update(buffering = true, loading = false, bufferedMs = 100, nowMs = 5_500))
        assertFalse(s.update(buffering = true, loading = false, bufferedMs = 100, nowMs = 9_000))
        assertTrue(s.update(buffering = true, loading = false, bufferedMs = 100, nowMs = 10_500))
    }

    @Test
    fun loadingOrBufferedOrPlayingIsNotStarved() {
        val s = StarvedBuffer(1_000)
        s.update(true, false, 0, 0)
        // A torrent read that waits for pieces is still loading: never cut off.
        assertFalse(s.update(buffering = true, loading = true, bufferedMs = 0, nowMs = 2_000))
        s.update(true, false, 0, 3_000)
        assertFalse(s.update(buffering = true, loading = false, bufferedMs = 800, nowMs = 5_000))
        s.update(true, false, 0, 6_000)
        assertFalse(s.update(buffering = false, loading = false, bufferedMs = 0, nowMs = 8_000))
        s.update(true, false, 0, 9_000)
        s.reset()
        assertFalse(s.update(buffering = true, loading = false, bufferedMs = 0, nowMs = 9_500))
    }

    @Test
    fun quickStartOnlyShortensTheFirstStartOfTheStableBuffer() {
        val custom = BufferPolicy.LiveDurations(1_500, 5_000, 500, 1_000)
        assertEquals(BufferPolicy.LiveDurations(2_000, 15_000, 750, 2_000), BufferPolicy.live(lowLatency = false, fastStart = true, custom = custom))
        assertEquals(BufferPolicy.LiveDurations(2_000, 15_000, 2_000, 2_000), BufferPolicy.live(lowLatency = false, fastStart = false, custom = custom))
        assertEquals(custom, BufferPolicy.live(lowLatency = true, fastStart = true, custom = custom))
    }

    @Test
    fun liveBufferValuesStayInTheOrderMedia3Needs() {
        val odd = BufferPolicy.LiveDurations(minMs = 1_000, maxMs = 400, playbackMs = 3_000, rebufferMs = 0)
        assertEquals(BufferPolicy.LiveDurations(1_000, 1_000, 1_000, 250), BufferPolicy.live(lowLatency = true, fastStart = false, custom = odd))
    }
}
