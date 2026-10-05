package com.enigmacast.domain.osd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OsdEngineTest {
    @Test
    fun sameBytesKeepTheSameHash() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        assertEquals(OsdHasher.crc32(bytes), OsdHasher.crc32(bytes.copyOf()))
        assertNotEquals(OsdHasher.crc32(bytes), OsdHasher.crc32(byteArrayOf(1, 2, 3, 4, 6)))
    }

    @Test
    fun pngColorTypeReportsAlpha() {
        val png = ByteArray(26)
        png[0] = 0x89.toByte()
        png[1] = 0x50
        png[2] = 0x4E
        png[3] = 0x47
        png[4] = 0x0D
        png[5] = 0x0A
        png[6] = 0x1A
        png[7] = 0x0A
        png[25] = 6
        assertTrue(PngInspector.hasAlphaChannel(png))
        png[25] = 2
        assertFalse(PngInspector.hasAlphaChannel(png))
    }

    @Test
    fun scaleFillsMatchingAspectAndFitsOtherwise() {
        assertEquals(OsdScaleMode.FILL_BOUNDS, OsdScale.choose(1280, 720, 1920, 1080))
        assertEquals(OsdScaleMode.FIT, OsdScale.choose(1280, 720, 1000, 1000))
        assertEquals(OsdScaleMode.FIT, OsdScale.choose(0, 720, 1920, 1080))
    }

    @Test
    fun stateMachineMovesFromFastToHidden() {
        var time = 1_000L
        val machine = OsdStateMachine(OsdConfig(), now = { time })
        machine.onRemoteInput()
        assertEquals(OsdPollState.ACTIVE_FAST, machine.state)
        machine.onSample(changed = true, empty = false)
        assertEquals(OsdPollState.ACTIVE_FAST, machine.state)
        time = 1_800L
        machine.onSample(changed = false, empty = false)
        assertEquals(OsdPollState.ACTIVE_SLOW, machine.state)
        time = 6_000L
        machine.onSample(changed = false, empty = false)
        assertEquals(OsdPollState.IDLE, machine.state)
        machine.onSample(changed = false, empty = true)
        assertEquals(OsdPollState.HIDDEN, machine.state)
    }
}
