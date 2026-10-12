package com.enigmacast.domain.tuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingGuardTest {
    private val rtl4 = "1:0:19:1B1D:802:2:C00000:0:0:0:"
    private val rtl5 = "1:0:19:1B1E:802:2:C00000:0:0:0:" // same transponder as RTL4
    private val npo1 = "1:0:19:17C0:44D:1:C00000:0:0:0:"
    private val me = "192.168.1.50"
    private val rec = PlannedRecording(rtl4, "RTL4 HD", 1000, 3000)

    private fun slot(i: Int, stream: String = "", rec: String = "") = TunerSlot(i, "T$i", "BCM (DVB-S2)", stream = stream, recording = rec)

    @Test fun transponderComparisonUsesTsidOnidNamespace() {
        assertTrue(RecordingGuard.sameTransponder(rtl4, rtl5))
        assertFalse(RecordingGuard.sameTransponder(rtl4, npo1))
        assertFalse(RecordingGuard.sameTransponder(rtl4, "4097:0:1:0:0:0:0:0:0:0:http%3a//x"))
    }

    @Test fun singleTunerHeldByOwnStreamSwitches() {
        val s = TunerSnapshot(listOf(slot(0, stream = "NPO1")), listOf(StreamClient(npo1, "NPO1", me, 0)), inStandby = true)
        assertEquals(StartAction.SwitchTo(rec), RecordingGuard.atRecordingStart(s, rec, npo1, setOf(me)))
    }

    @Test fun freeTunerLeavesStreamAlone() {
        val s = TunerSnapshot(listOf(slot(0, stream = "NPO1"), slot(1)), listOf(StreamClient(npo1, "NPO1", me, 0)), inStandby = true)
        assertEquals(StartAction.None, RecordingGuard.atRecordingStart(s, rec, npo1, setOf(me)))
    }

    @Test fun sameTransponderNeedsNoSwitch() {
        val s = TunerSnapshot(listOf(slot(0, stream = "RTL5")), listOf(StreamClient(rtl5, "RTL5", me, 0)), inStandby = true)
        assertEquals(StartAction.None, RecordingGuard.atRecordingStart(s, rec, rtl5, setOf(me)))
    }

    @Test fun multiUserOtherDeviceFillsTheSecondTuner() {
        val s = TunerSnapshot(
            listOf(slot(0, stream = "NPO1"), slot(1, stream = "X")),
            listOf(StreamClient(npo1, "NPO1", me, 0), StreamClient("1:0:19:1:2:3:C00000:0:0:0:", "X", "192.168.1.60", 1)),
            inStandby = true,
        )
        assertEquals(StartAction.SwitchTo(rec), RecordingGuard.atRecordingStart(s, rec, npo1, setOf(me)))
    }

    @Test fun zapDuringRecordingOnlySameTransponder() {
        val s = TunerSnapshot(listOf(slot(0, rec = "RTL4")), inStandby = true, recordingReferences = listOf(rtl4))
        val running = listOf(rec)
        assertEquals(ZapDecision.Allowed, RecordingGuard.zap(s, rtl5, running, setOf(me)))
        assertEquals(ZapDecision.Blocked(rec), RecordingGuard.zap(s, npo1, running, setOf(me)))
        assertEquals(ZapDecision.Allowed, RecordingGuard.zap(s, npo1, emptyList(), setOf(me)))
        val two = s.copy(tuners = s.tuners + slot(1))
        assertEquals(ZapDecision.Allowed, RecordingGuard.zap(two, npo1, running, setOf(me)))
    }

    @Test fun dueWindow() {
        assertEquals(listOf(rec), RecordingGuard.dueNow(listOf(rec), 990))
        assertTrue(RecordingGuard.dueNow(listOf(rec), 900).isEmpty())
        assertTrue(RecordingGuard.dueNow(listOf(rec), 1100).isEmpty())
    }
}
