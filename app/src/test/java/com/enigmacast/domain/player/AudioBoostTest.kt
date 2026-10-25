package com.enigmacast.domain.player

import com.enigmacast.domain.model.LastChannel
import com.enigmacast.domain.xtream.XtreamItem
import com.enigmacast.domain.xtream.XtreamSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class AudioBoostTest {
    @Test fun buttonCyclesOffThroughFiveAndBack() {
        val seen = generateSequence(0) { AudioBoost.next(it) }.take(7).toList()
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 0), seen)
        assertEquals(0, AudioBoost.next(9))
        assertEquals(1, AudioBoost.next(-3))
    }

    @Test fun eachStepIsThreeDecibels() {
        assertEquals(1f, AudioBoost.gain(0), 0f)
        assertEquals(15, AudioBoost.db(5))
        assertEquals(1.4125f, AudioBoost.gain(1), 0.001f)
        assertEquals(5.6234f, AudioBoost.gain(5), 0.001f)
        assertEquals(AudioBoost.gain(5), AudioBoost.gain(8), 0f)
    }

    @Test fun channelKeysAreStableAndProviderAware() {
        val a = AudioBoost.satelliteKey("1:0:19:1B1F:802:2:11A0000:0:0:0:")
        assertEquals(a, AudioBoost.satelliteKey(" 1:0:19:1b1f:802:2:11a0000:0:0:0: "))
        assertTrue(a.startsWith("e2:"))
        assertEquals("", AudioBoost.satelliteKey(null))
        assertEquals("", AudioBoost.satelliteKey(" "))
        assertEquals("iptv:p1:42", AudioBoost.iptvKey("p1", "42"))
        assertTrue(AudioBoost.iptvKey("p1", "42") != AudioBoost.iptvKey("p2", "42"))
        assertEquals("", AudioBoost.iptvKey("", "42"))
        assertEquals("", AudioBoost.iptvKey("p1", ""))
    }

    @Test fun limiterPassesUnityUntouched() {
        val limiter = BoostLimiter(48_000)
        val samples = FloatArray(960) { sin(it / 10f) * 0.99f }
        val copy = samples.copyOf()
        limiter.process(samples, 2)
        assertTrue(limiter.idle)
        assertTrue(samples.contentEquals(copy))
    }

    @Test fun limiterNeverGoesOverTheCeiling() {
        val limiter = BoostLimiter(48_000)
        limiter.target = AudioBoost.gain(5)
        limiter.reset()
        val samples = FloatArray(48_000) { sin(it / 7f) * 0.9f }
        limiter.process(samples, 2)
        assertTrue(samples.all { abs(it) <= BoostLimiter.CEILING + 1e-6f })
    }

    @Test fun limiterMakesQuietSoundLouder() {
        val limiter = BoostLimiter(48_000)
        limiter.target = AudioBoost.gain(3)
        limiter.reset()
        val samples = FloatArray(4_800) { 0.05f }
        limiter.process(samples, 1)
        assertEquals(0.05f * AudioBoost.gain(3), samples.last(), 1e-4f)
    }

    @Test fun limiterGlidesToANewLevel() {
        val limiter = BoostLimiter(48_000)
        limiter.target = AudioBoost.gain(5)
        val samples = FloatArray(48_000) { 0.01f }
        limiter.process(samples, 1)
        // No jump on the first sample, the full gain after the glide.
        assertTrue(samples.first() < 0.0101f)
        assertEquals(0.01f * AudioBoost.gain(5), samples.last(), 1e-4f)
    }

    @Test fun previousSatelliteChannelToggles() {
        val a = LastChannel("1:0:19:1B1F:802:2:11A0000:0:0:0:", "NPO 1")
        val b = LastChannel("1:0:19:1B1D:802:2:11A0000:0:0:0:", "RTL 4")
        // Watching B after A: the recent list is newest first.
        var recent = RecentChannels.push(RecentChannels.push(emptyList(), a), b)
        assertEquals(a, PreviousChannel.satellite(recent, b.reference))
        recent = RecentChannels.push(recent, a)
        assertEquals(b, PreviousChannel.satellite(recent, a.reference.lowercase()))
        assertNull(PreviousChannel.satellite(listOf(a), a.reference))
        assertNull(PreviousChannel.satellite(recent, null))
    }

    @Test fun previousIptvChannelKeepsTheProvider() {
        val a = XtreamItem("1", "A", XtreamSection.LIVE, "c", providerId = "p1")
        val sameIdOtherProvider = a.copy(providerId = "p2")
        assertEquals(a, PreviousChannel.iptv(a, sameIdOtherProvider))
        assertNull(PreviousChannel.iptv(a, a.copy(name = "A HD")))
        assertNull(PreviousChannel.iptv(null, a))
    }
}
