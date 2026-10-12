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
    fun repeatedStreamNumbersContinueAndAGapStays() {
        val numbers = listOf(1241, 1242, 1243, 1244, 1245, 1246, 1248, 1248, 1248, 1249, 1249)
        val streams = numbers.mapIndexed { index, number ->
            BouquetEntry("Stream $index", "4097:0:1:0:0:0:0:0:0:0:http://cdn/$index", number = number)
        }
        assertEquals(
            listOf(1241, 1242, 1243, 1244, 1245, 1246, 1248, 1249, 1250, 1251, 1252),
            ChannelNavigator.increasingNumbers(streams).map { it.number },
        )
    }

    @Test
    fun channelUpWalksInternetStreamsThatShareServiceFields() {
        val prefix = "4097:0:1:1641:CDCB:EC:0:0:0:0:"
        val streams = listOf("One", "Two", "Three").map { name ->
            BouquetEntry(name, "${prefix}https3a//cdn.example/${name.lowercase()}.m3u8:$name")
        }
        assertEquals("Two", ChannelNavigator.step(streams, streams[0].reference, 1)?.name)
        assertEquals("Three", ChannelNavigator.step(streams, streams[1].reference, 1)?.name)
        assertEquals("One", ChannelNavigator.step(streams, streams[2].reference, 1)?.name)
    }

    @Test
    fun channelUpSkipsASectionLabelThatCopiesThePreviousStream() {
        val zulthe = BouquetEntry(
            "RTV Zulthe (Westerkwartier)",
            "4097:0:1:1BC9:C86:600:0:0:0:0:https3a//cdn/zulthe.m3u8:RTV Zulthe (Westerkwartier)",
            number = 5716,
        )
        val label = BouquetEntry(
            "+++Friesland+++",
            "1:0:21E:0:0:0:0:0:0:0:https3a//cdn/zulthe.m3u8:+++Friesland+++",
            number = 5717,
        )
        val next = BouquetEntry(
            "Omrop Fryslân",
            "4097:0:1:524D:C99:3:0:0:0:0:https3a//cdn/fryslan.m3u8:Omrop Fryslân",
            number = 5718,
        )
        val channels = listOf(zulthe, label, next)
        assertEquals("Omrop Fryslân", ChannelNavigator.step(channels, zulthe.reference, 1)?.name)
        assertEquals("RTV Zulthe (Westerkwartier)", ChannelNavigator.step(channels, next.reference, -1)?.name)
    }

    @Test
    fun duplicateUrlsStepFromTheCurrentRow() {
        val prefix = "4097:0:1:1641:CDCB:EC:0:0:0:0:"
        val shared = "https%3a//cdn.example/same.m3u8"
        val streams = listOf(
            BouquetEntry("First", "$prefix$shared:First"),
            BouquetEntry("Second", "$prefix$shared:Second"),
            BouquetEntry("Third", "${prefix}https3a//cdn.example/other.m3u8:Third"),
        )
        assertEquals("Second", ChannelNavigator.step(streams, streams[0].reference, 1)?.name)
        assertEquals("Third", ChannelNavigator.step(streams, streams[1].reference, 1)?.name)
    }

    @Test
    fun streamsThatShareTheServiceFieldsKeepTheirOwnPlace() {
        val first = BouquetEntry("One", "4097:0:1:0:0:0:0:0:0:0:http://cdn/one")
        val second = BouquetEntry("Two", "4097:0:1:0:0:0:0:0:0:0:http://cdn/two")
        val streams = listOf(first, second)
        assertEquals(1, ChannelNavigator.numberOf(streams, first))
        assertEquals(2, ChannelNavigator.numberOf(streams, second))
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
