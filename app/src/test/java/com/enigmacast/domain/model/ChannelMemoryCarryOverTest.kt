package com.enigmacast.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelMemoryCarryOverTest {
    private val npo = LastChannel(reference = "1:0:19:1B6C:C88:3:EB0000:0:0:0:", name = "NPO 1")
    private val rtl = LastChannel(reference = "1:0:19:1B6D:C88:3:EB0000:0:0:0:", name = "RTL 4")

    @Test fun sameListsCopyLastChannelToNewBox() {
        val s = AppSettings(lastChannels = mapOf("old" to npo), recentByReceiver = mapOf("old" to listOf(npo)))
        val out = ChannelMemory.carryOver(s, "old", "new")
        assertEquals(npo, out.lastChannels["new"])
        assertEquals(listOf(npo), out.recentByReceiver["new"])
        assertEquals(npo, out.lastChannels["old"])
    }

    @Test fun newBoxKeepsItsOwnMemory() {
        val s = AppSettings(lastChannels = mapOf("old" to npo, "new" to rtl))
        assertEquals(rtl, ChannelMemory.carryOver(s, "old", "new").lastChannels["new"])
    }

    @Test fun withoutCarryOverMemoryStaysPerReceiver() {
        val s = AppSettings(lastChannels = mapOf("old" to npo))
        assertNull(s.lastChannels["new"])
        assertEquals(s, ChannelMemory.carryOver(s, "old", "old"))
    }
}
