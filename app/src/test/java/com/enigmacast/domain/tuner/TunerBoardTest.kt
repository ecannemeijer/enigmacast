package com.enigmacast.domain.tuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TunerBoardTest {
    private val snapshot = TunerSnapshot(
        tuners = listOf(
            TunerSlot(0, "Tuner A", "DVB-C", live = "NPO 1 HD"),
            TunerSlot(1, "Tuner B", "DVB-C", stream = "RTL 4 HD"),
            TunerSlot(2, "Tuner C", "DVB-C"),
        ),
        streams = listOf(
            StreamClient("1:0:19:RTL4", "RTL 4 HD", "192.168.1.50", tunerNumber = 1),
            StreamClient("1:0:19:SBS6", "SBS 6", "::ffff:192.168.1.77", tunerNumber = 1),
        ),
        inStandby = false,
        liveReference = "1:0:19:NPO1",
    )

    @Test fun mapsLiveAndStreamsToTheirTuners() {
        val cards = TunerBoard.cards(snapshot, setOf("192.168.1.77"))
        assertEquals(listOf("A", "B", "C"), cards.map { it.letter })
        assertEquals(TunerUseKind.LIVE, cards[0].primary?.kind)
        assertEquals("1:0:19:NPO1", cards[0].primary?.reference)
        assertEquals(listOf(TunerUseKind.OTHER_STREAM, TunerUseKind.OWN_STREAM), cards[1].uses.map { it.kind })
        assertTrue(cards[2].idle)
    }

    @Test fun stopOptions() {
        val cards = TunerBoard.cards(snapshot, setOf("192.168.1.77"))
        assertEquals(StopOption.STANDBY_RECEIVER, cards[0].stopOption())
        assertEquals(StopOption.STOP_OWN, cards[1].stopOption())
        val other = TunerBoard.cards(snapshot, emptySet())
        assertEquals(StopOption.NOT_POSSIBLE_OTHER, other[1].stopOption())
    }

    @Test fun standbyHidesLivePicture() {
        val cards = TunerBoard.cards(snapshot.copy(inStandby = true), emptySet())
        assertTrue(cards[0].idle)
    }

    @Test fun recordingUsesRunningTimer() {
        val rec = TunerSnapshot(listOf(TunerSlot(0, "Tuner A", "DVB-C", recording = "Journaal (NPO 1)")), recordingReferences = listOf("1:0:19:NPO1"))
        val card = TunerBoard.cards(rec, emptySet(), listOf(RunningTimer("1:0:19:NPO1", "Journaal"))).single()
        assertEquals(TunerUseKind.RECORDING, card.primary?.kind)
        assertEquals("1:0:19:NPO1", card.primary?.reference)
        assertEquals(StopOption.NOT_POSSIBLE_RECORDING, card.stopOption())
    }

    @Test fun todayScheduleFiltersAndSorts() {
        val items = listOf(
            ScheduleItem(500, 600, "b", "Later", ScheduleKind.BOX_TIMER),
            ScheduleItem(150, 300, "a", "Running", ScheduleKind.TV_RECORDING),
            ScheduleItem(2000, 2100, "c", "Tomorrow", ScheduleKind.AUTOSWITCH),
            ScheduleItem(50, 80, "d", "Done yesterday", ScheduleKind.BOX_TIMER),
        )
        val today = TodaySchedule.merge(items, dayStart = 100, dayEnd = 1000, nowSec = 200)
        assertEquals(listOf("Running", "Later"), today.map { it.title })
    }
}
