package com.enigmacast.domain.recording

import com.enigmacast.data.openwebif.OpenWebifJson
import com.enigmacast.domain.tuner.ScheduleItem
import com.enigmacast.domain.tuner.ScheduleKind
import com.enigmacast.domain.tuner.TodaySchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerMatchTest {
    private val ref = "1:0:19:1B1D:802:2:11A0000:0:0:0:"
    private val rec = Recording("a", "GTST", "RTL4 HD", ref, 42777, 72000, 73740, RecordingPlace.RECEIVER, RecordingStatus.SCHEDULED, "r")

    @Test fun matchesByEventIdDespiteMargins() =
        assertTrue(TimerMatch.same(ref, 72000 - 180, 73740 + 300, 42777, rec))

    @Test fun matchesByOverlapWithoutEventId() =
        assertTrue(TimerMatch.same(ref, 72000 - 180, 73740 + 300, -1, rec.copy(eventId = -1)))

    @Test fun otherServiceOrEventDoesNotMatch() {
        assertFalse(TimerMatch.same("1:0:19:AAAA:1:1:1:0:0:0:", 71820, 74040, 42777, rec))
        assertFalse(TimerMatch.same(ref, 71820, 74040, 999, rec))
    }

    @Test fun marginsInSecondsOrMinutes() {
        assertEquals(180L, OpenWebifJson.marginSec("180"))
        assertEquals(300L, OpenWebifJson.marginSec("5"))
        assertEquals(0L, OpenWebifJson.marginSec(null))
    }

    @Test fun mergeKeepsOneEntryPerProgramme() {
        val a = ScheduleItem(72000, 73740, "RTL4 HD", "GTST", ScheduleKind.BOX_RECORDING, reference = ref, recordingId = "a")
        val b = ScheduleItem(72000, 73740, "RTL4 HD", "GTST", ScheduleKind.BOX_TIMER, reference = ref)
        assertEquals(1, TodaySchedule.merge(listOf(a, b), 0, 100000, 0).size)
    }
}
