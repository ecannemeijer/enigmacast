package com.enigmacast.playback

import com.enigmacast.domain.model.VideoCompatibility
import com.enigmacast.domain.model.VideoDecoderMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoOutputTest {
    @Test fun xiaomiStickIsQuirk() {
        assertTrue(VideoQuirks.isKnownGarbler("Xiaomi", "Xiaomi", "MiTV-AFKR0", "amlogic", ""))
        assertTrue(VideoQuirks.isKnownGarbler("Xiaomi", "Xiaomi", "MiTV-AFKR0", "sc2", ""))
        assertTrue(VideoQuirks.isKnownGarbler("Foo", "Foo", "X", "g12a", ""))
        assertFalse(VideoQuirks.isKnownGarbler("NVIDIA", "NVIDIA", "SHIELD Android TV", "darcy", ""))
        assertFalse(VideoQuirks.isKnownGarbler("Amazon", "Amazon", "AFTKA", "mt8696", ""))
    }

    @Test fun compatibility() {
        assertTrue(VideoOutput.compatibilityActive(VideoCompatibility.AUTO, true))
        assertFalse(VideoOutput.compatibilityActive(VideoCompatibility.AUTO, false))
        assertTrue(VideoOutput.useSurfaceView(VideoCompatibility.AUTO))
        assertFalse(VideoOutput.useSurfaceView(VideoCompatibility.OFF))
    }

    @Test fun decoderOrder() {
        val names = listOf("c2.android.avc" to false, "OMX.amlogic.avc" to true)
        assertEquals(listOf("OMX.amlogic.avc", "c2.android.avc"), VideoOutput.order(VideoDecoderMode.AUTO, names))
        assertEquals(listOf("c2.android.avc"), VideoOutput.order(VideoDecoderMode.SOFTWARE, names))
        assertEquals(listOf("OMX.amlogic.avc"), VideoOutput.order(VideoDecoderMode.HARDWARE, names))
    }
}
