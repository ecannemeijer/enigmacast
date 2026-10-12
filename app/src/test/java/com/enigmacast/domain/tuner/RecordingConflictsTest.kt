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

class StreamFailureConflictTest {
    private val rai1 = "1:0:19:D49:1450:13E:820000:0:0:0:"
    private val npo1 = "1:0:19:17C0:44D:1:C00000:0:0:0:"
    private val rec = PlannedRecording(rai1, "Rai 1 HD", 1000, 5000, "La volta buona", timer = true)

    @Test fun failureWithFreeTunerClaimStillOffersRecording() {
        // Box state from the field: tuner A records Rai 1, tuner B listed free, no streams.
        for (h in listOf(StreamHealth.NO_TUNER, StreamHealth.NO_PICTURE, StreamHealth.FAILED)) {
            assertEquals(listOf(rec), RecordingConflicts.afterStreamFailure(h, listOf(rec), npo1))
        }
        assertTrue(RecordingConflicts.afterStreamFailure(StreamHealth.PLAYING, listOf(rec), npo1).isEmpty())
        assertTrue(RecordingConflicts.afterStreamFailure(StreamHealth.NO_TUNER, emptyList(), npo1).isEmpty())
    }

    @Test fun snapshotFallbackNamesRunningRecordings() {
        val snap = TunerSnapshot(listOf(TunerSlot(0, "A", "Si2166D (DVB-S2X)", recording = "13.0° E / Rai 1 HD"), TunerSlot(1, "B", "Si2166D (DVB-S2X)")), inStandby = true, recordingReferences = listOf(rai1))
        assertEquals(listOf(PlannedRecording(rai1, "Rai 1 HD", 0, 0)), RecordingConflicts.fromSnapshot(snap) { "Rai 1 HD" })
    }
}
