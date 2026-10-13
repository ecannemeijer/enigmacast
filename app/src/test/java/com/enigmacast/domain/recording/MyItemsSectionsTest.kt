package com.enigmacast.domain.recording

import com.enigmacast.domain.model.ReceiverLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class MyItemsSectionsTest {
    private fun rec(id: String, place: RecordingPlace, status: RecordingStatus, file: String = "", begin: Long = 0) =
        Recording(id, id, "RTL4", "", -1, begin, begin + 60, place, status, "r", fileName = file)

    @Test fun placeDecidesTheSection() {
        val tv = rec("tv", RecordingPlace.TV, RecordingStatus.READY)
        val ourDone = rec("box1", RecordingPlace.RECEIVER, RecordingStatus.READY, "/hdd/a.ts", 10)
        val ourNext = rec("box2", RecordingPlace.RECEIVER, RecordingStatus.SCHEDULED)
        val other = rec("bm:/hdd/b.ts", RecordingPlace.RECEIVER, RecordingStatus.READY, "/hdd/b.ts", 20)
        val dupe = rec("bm:/hdd/a.ts", RecordingPlace.RECEIVER, RecordingStatus.READY, "/hdd/a.ts", 10)
        val s = MyItemsSections.split(listOf(tv, ourDone, ourNext), listOf(other, dupe), emptyList())
        assertEquals(listOf(tv), s.device)
        assertEquals(listOf("bm:/hdd/b.ts", "box1"), s.onReceiver.map { it.id })
        assertEquals(listOf(ourNext), s.scheduledOnReceiver)
    }

    @Test fun receiverLabel() {
        assertEquals("Octagon SF8008", ReceiverLabel.of("Octagon", "SF8008"))
        assertEquals("Octagon SF8008", ReceiverLabel.of("Octagon", "Octagon SF8008"))
        assertEquals("SF8008", ReceiverLabel.of("", "SF8008"))
    }
}
