package com.enigmacast.domain.tuner

import com.enigmacast.domain.model.BouquetEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelNavigatorTest {
    private val channels = listOf(
        BouquetEntry("NPO 1", "1:0:19:4651:3FC:1:C00000:0:0:0:", number = 1),
        BouquetEntry("NPO 2", "1:0:19:4652:3FC:1:C00000:0:0:0:", number = 2),
        BouquetEntry("RTL 4", "1:0:19:7D5:3F0:1:C00000:0:0:0:", number = 4),
    )

    @Test
    fun stepsWrapAroundTheList() {
        assertEquals("NPO 2", ChannelNavigator.step(channels, channels[0].reference, 1)?.name)
        assertEquals("NPO 1", ChannelNavigator.step(channels, channels[2].reference, 1)?.name)
        assertEquals("RTL 4", ChannelNavigator.step(channels, channels[0].reference, -1)?.name)
        assertEquals("NPO 1", ChannelNavigator.step(channels, "unknown", 1)?.name)
        assertNull(ChannelNavigator.step(emptyList(), "x", 1))
    }

    @Test
    fun numbersComeFromTheReceiverOrThePosition() {
        assertEquals("RTL 4", ChannelNavigator.byNumber(channels, 4)?.name)
        assertNull(ChannelNavigator.byNumber(channels, 3))
        val unnumbered = channels.map { it.copy(number = 0) }
        assertEquals("RTL 4", ChannelNavigator.byNumber(unnumbered, 3)?.name)
        assertEquals(3, ChannelNavigator.numberOf(unnumbered, unnumbered[2]))
    }

    @Test
    fun numberEntryCollectsUpToFourDigits() {
        val entry = NumberEntry()
        assertFalse(entry.push(0))
        assertFalse(entry.push(1))
        assertFalse(entry.push(2))
        assertEquals("12", entry.digits)
        assertEquals(12, entry.take())
        assertEquals("", entry.digits)
        entry.push(1); entry.push(0); entry.push(0)
        assertTrue(entry.push(1))
        assertEquals(1001, entry.take())
    }
}
