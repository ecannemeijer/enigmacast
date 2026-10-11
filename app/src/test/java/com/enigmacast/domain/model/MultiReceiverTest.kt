package com.enigmacast.domain.model

import com.enigmacast.data.openwebif.openWebifJson
import com.enigmacast.domain.recording.Recording
import com.enigmacast.domain.recording.RecordingPlace
import com.enigmacast.domain.recording.RecordingStatus
import com.enigmacast.domain.recording.forReceiver
import com.enigmacast.domain.recording.receiverTimers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverListTest {
    private val living = ReceiverConfig(id = "living", name = "Living room", host = "192.168.1.10")
    private val bed = ReceiverConfig(id = "bed", name = "Bedroom", host = "192.168.1.20")
    private val vacation = ReceiverConfig(id = "vacation", name = "Vacation", host = "10.0.0.5")

    @Test
    fun selectMarksThatBox() {
        val settings = AppSettings(receivers = listOf(living, bed), activeReceiverId = "living")
        val next = ReceiverList.select(settings, "bed")
        assertEquals("bed", next.activeReceiverId)
        assertEquals("Bedroom", next.activeReceiver()?.name)
    }

    @Test
    fun selectIgnoresAnUnknownBox() {
        val settings = AppSettings(receivers = listOf(living), activeReceiverId = "living")
        assertEquals(settings, ReceiverList.select(settings, "missing"))
    }

    @Test
    fun removeActivePicksTheNextBox() {
        val settings = AppSettings(
            receivers = listOf(living, bed, vacation),
            activeReceiverId = "bed",
            lastChannels = mapOf("bed" to LastChannel("1:0:1", "NPO 1", "bouq")),
            recentByReceiver = mapOf("bed" to listOf(LastChannel("1:0:1", "NPO 1", "bouq"))),
        )
        val next = ReceiverList.remove(settings, "bed")
        assertEquals(listOf("living", "vacation"), next.receivers.map { it.id })
        assertEquals("vacation", next.activeReceiverId)
        assertTrue(next.lastChannels.isEmpty())
        assertTrue(next.recentByReceiver.isEmpty())
    }

    @Test
    fun removeLastLeavesNone() {
        val settings = AppSettings(receivers = listOf(living), activeReceiverId = "living")
        val next = ReceiverList.remove(settings, "living")
        assertTrue(next.receivers.isEmpty())
        assertNull(next.activeReceiverId)
        assertNull(next.activeReceiver())
    }

    @Test
    fun nextBoxFollowsTheListAndWraps() {
        val boxes = listOf(living, bed, vacation)
        assertEquals("bed", ReceiverList.nextAfter(boxes, "living", setOf("living"))?.id)
        assertEquals("vacation", ReceiverList.nextAfter(boxes, "living", setOf("living", "bed"))?.id)
        assertNull(ReceiverList.nextAfter(boxes, "living", setOf("living", "bed", "vacation")))
        assertEquals("living", ReceiverList.nextAfter(boxes, "vacation", emptySet())?.id)
    }

    @Test
    fun aSingleBoxHasNoFailover() {
        assertNull(ReceiverList.nextAfter(listOf(living), "living", emptySet()))
    }

    @Test
    fun removeAnotherBoxKeepsTheChoice() {
        val settings = AppSettings(receivers = listOf(living, bed), activeReceiverId = "living")
        val next = ReceiverList.remove(settings, "bed")
        assertEquals("living", next.activeReceiverId)
        assertEquals(listOf("living"), next.receivers.map { it.id })
    }
}

class ChannelMemoryTest {
    private val living = ReceiverConfig(id = "living", name = "Living room", host = "192.168.1.10")
    private val bed = ReceiverConfig(id = "bed", name = "Bedroom", host = "192.168.1.20")

    @Test
    fun legacyChannelMovesOntoTheActiveBox() {
        val saved = """
            {"receivers":[{"id":"living","name":"Living room","host":"192.168.1.10"}],
            "activeReceiverId":"living",
            "lastChannel":{"reference":"1:0:1","name":"NPO 1","bouquetReference":"1:7:1"},
            "recentChannels":[{"reference":"1:0:1","name":"NPO 1","bouquetReference":"1:7:1"}]}
        """.trimIndent()
        val settings = ChannelMemory.migrate(openWebifJson.decodeFromString(saved))
        assertEquals("NPO 1", ChannelMemory.lastFor(settings, "living").name)
        assertEquals("1:7:1", ChannelMemory.lastFor(settings, "living").bouquetReference)
        assertEquals(listOf("NPO 1"), ChannelMemory.recentFor(settings, "living").map { it.name })
        assertEquals("", settings.lastChannel.reference)
        assertTrue(settings.recentChannels.isEmpty())
        assertTrue(!ChannelMemory.needsMigration(settings))
    }

    @Test
    fun anotherBoxDoesNotInheritTheChannel() {
        val migrated = ChannelMemory.migrate(
            AppSettings(
                receivers = listOf(living, bed),
                activeReceiverId = "living",
                lastChannel = LastChannel("1:0:1", "NPO 1", "1:7:1"),
                recentChannels = listOf(LastChannel("1:0:1", "NPO 1", "1:7:1")),
            ),
        )
        val switched = ReceiverList.select(migrated, "bed")
        assertEquals("1:0:1", ChannelMemory.lastFor(switched, "living").reference)
        assertEquals("", ChannelMemory.lastFor(switched, "bed").reference)
        assertTrue(ChannelMemory.recentFor(switched, "bed").isEmpty())
    }

    @Test
    fun rememberStaysOnThatBox() {
        val settings = AppSettings(receivers = listOf(living, bed), activeReceiverId = "living")
        val remembered = ChannelMemory.remember(settings, "bed", LastChannel("1:0:2", "NPO 2", "bouq"))
        assertEquals("NPO 2", ChannelMemory.lastFor(remembered, "bed").name)
        assertEquals("", ChannelMemory.lastFor(remembered, "living").reference)
        assertEquals(listOf("NPO 2"), ChannelMemory.recentFor(remembered, "bed").map { it.name })
    }
}

class RecordingScopeTest {
    @Test
    fun anotherBoxIsNotShownOrRefreshed() {
        val livingTimer = item("living", RecordingPlace.RECEIVER)
        val bedTimer = item("bed", RecordingPlace.RECEIVER)
        val livingFile = item("living", RecordingPlace.TV)
        val items = listOf(livingTimer, bedTimer, livingFile)
        assertEquals(listOf(livingTimer, livingFile), items.forReceiver("living"))
        assertEquals(listOf(livingTimer), items.receiverTimers("living"))
        assertEquals(listOf(bedTimer), items.receiverTimers("bed"))
        assertTrue(items.forReceiver("").isEmpty())
    }

    private fun item(receiverId: String, place: RecordingPlace) = Recording(
        id = "$receiverId-$place",
        title = "Show",
        channelName = "NPO 1",
        reference = "1:0:1",
        eventId = 1,
        beginEpochSec = 10,
        endEpochSec = 20,
        place = place,
        status = RecordingStatus.RECORDING,
        receiverId = receiverId,
    )
}
