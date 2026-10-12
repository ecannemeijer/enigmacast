package com.enigmacast.domain.tuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingConflictsTest {
    private val rtl4 = "1:0:19:1B1D:802:2:C00000:0:0:0:"
    private val rtl5 = "1:0:19:1B1E:802:2:C00000:0:0:0:"
    private val npo1 = "1:0:19:17C0:44D:1:C00000:0:0:0:"
    private val sbs6 = "1:0:19:1B26:7F0:3:C00000:0:0:0:"
    private val gtst = PlannedRecording(rtl4, "RTL4 HD", 1000, 3300, "GTST", programmeEndEpochSec = 3000, timer = true)
    private val news = PlannedRecording(sbs6, "SBS6", 1000, 2000, "Nieuws", recordingId = "app1")

    @Test fun listsEachRecordingOnAnotherTransponder() {
        assertEquals(listOf(news, gtst), RecordingConflicts.blockers(listOf(gtst, news), npo1))
    }

    @Test fun noConflictOnSharedTransponderOrWithoutRecordings() {
        assertTrue(RecordingConflicts.blockers(listOf(gtst), rtl5).isEmpty())
        assertTrue(RecordingConflicts.blockers(emptyList(), npo1).isEmpty())
        assertTrue(RecordingConflicts.blockers(listOf(gtst), "4097:0:1:0:0:0:0:0:0:0:http%3a//x").isEmpty())
    }

    @Test fun untilUsesProgrammeEnd() {
        assertEquals(3000L, gtst.untilEpochSec)
        assertEquals(2000L, news.untilEpochSec)
    }
}
