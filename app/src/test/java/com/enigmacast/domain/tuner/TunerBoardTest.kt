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

    @Test fun aSecondTransponderWithoutATunerNumberLeavesTheFirstCard() {
        // OpenWebif on 192.168.1.209 reports tuner_number only for the first stream client
        // and leaves Tuner B's label empty, while the second frequency is really playing.
        val rtl5 = "1:0:19:1B6F:C88:3:EB0000:0:0:0:"
        val npo1 = "1:0:19:5225:C99:3:EB0000:0:0:0:"
        val snap = TunerSnapshot(
            tuners = listOf(
                TunerSlot(0, "Tuner A", "DVB-S2", stream = "23.5°E / RTL5 HD"),
                TunerSlot(1, "Tuner B", "DVB-S2"),
            ),
            streams = listOf(
                StreamClient(rtl5, "RTL5 HD", "::ffff:192.168.1.100", tunerNumber = 0),
                StreamClient(npo1, "NPO1 HD", "::ffff:192.168.1.100"),
            ),
            inStandby = true,
        )
        val cards = TunerBoard.cards(snap, setOf("192.168.1.100"))
        assertEquals("RTL5 HD", cards[0].primary?.channelName)
        assertEquals("NPO1 HD", cards[1].primary?.channelName)
        assertEquals(false, cards[1].idle)
    }

    @Test fun aShorterNameDoesNotStickToTheTunersLabel() {
        val npo1hd = "1:0:19:5225:C99:3:EB0000:0:0:0:"
        val npo2 = "1:0:19:17C0:C82:3:EB0000:0:0:0:"
        val snap = TunerSnapshot(
            tuners = listOf(
                TunerSlot(0, "Tuner A", "DVB-S2", stream = "23.5°E / NPO1 HD"),
                TunerSlot(1, "Tuner B", "DVB-S2"),
            ),
            streams = listOf(
                StreamClient(npo1hd, "NPO1 HD", "::ffff:192.168.1.100", tunerNumber = 0),
                StreamClient(npo2, "NPO1", "::ffff:192.168.1.100"),
            ),
        )
        val cards = TunerBoard.cards(snap, emptySet())
        assertEquals("NPO1 HD", cards[0].primary?.channelName)
        assertEquals("NPO1", cards[1].primary?.channelName)
    }

    @Test fun streamsWithoutATunerNumberTakeOneCardPerTransponder() {
        val sbs = "1:0:19:1B1D:3F6:1:C00000:0:0:0:"
        val npo = "1:0:19:4651:3FC:1:C00000:0:0:0:"
        val snap = TunerSnapshot(
            tuners = listOf(TunerSlot(0, "A", "DVB-S2"), TunerSlot(1, "B", "DVB-S2")),
            streams = listOf(
                StreamClient(sbs, "SBS6 HD", "::ffff:192.168.1.102"),
                StreamClient(npo, "NPO 1", "::ffff:192.168.1.100"),
            ),
        )
        val cards = TunerBoard.cards(snap, emptySet())
        assertEquals("SBS6 HD", cards[0].primary?.channelName)
        assertEquals("NPO 1", cards[1].primary?.channelName)
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
