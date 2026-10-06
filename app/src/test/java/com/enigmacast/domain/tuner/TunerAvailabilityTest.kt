package com.enigmacast.domain.tuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TunerAvailabilityTest {
    private val npo1 = "1:0:19:4651:3FC:1:C00000:0:0:0:"
    private val npo2 = "1:0:19:4652:3FC:1:C00000:0:0:0:"
    private val rtl4 = "1:0:19:7D5:3F0:1:C00000:0:0:0:"
    private val sbs6 = "1:0:19:1B1D:3F6:1:C00000:0:0:0:"
    private val me = setOf("192.168.1.30")

    private fun twoTuners(vararg slots: TunerSlot) = listOf(
        slots.getOrNull(0) ?: TunerSlot(0, "Tuner A", "BCM45208 (DVB-S2)"),
        slots.getOrNull(1) ?: TunerSlot(1, "Tuner B", "BCM45208 (DVB-S2)"),
    )

    @Test
    fun standbyReceiverWithoutStreamsHasBothTunersFree() {
        val snapshot = TunerSnapshot(twoTuners(), inStandby = true, liveReference = "")
        assertEquals(TunerVerdict.Free(2, 2), TunerAvailability.evaluate(snapshot, npo1, me))
    }

    @Test
    fun oneOtherStreamLeavesOneTuner() {
        val snapshot = TunerSnapshot(
            twoTuners(),
            streams = listOf(StreamClient(rtl4, "RTL 4", "::ffff:192.168.1.21")),
            inStandby = true,
        )
        assertEquals(TunerVerdict.Free(1, 2), TunerAvailability.evaluate(snapshot, npo1, me))
    }

    @Test
    fun twoOtherStreamsOnDifferentTranspondersFillTwoTuners() {
        val snapshot = TunerSnapshot(
            twoTuners(),
            streams = listOf(
                StreamClient(rtl4, "RTL 4", "192.168.1.21"),
                StreamClient(sbs6, "SBS6", "192.168.1.22"),
            ),
            inStandby = true,
        )
        val verdict = TunerAvailability.evaluate(snapshot, npo1, me)
        assertTrue(verdict is TunerVerdict.Full)
        verdict as TunerVerdict.Full
        assertEquals(2, verdict.used)
        assertEquals(2, verdict.total)
        assertEquals(2, verdict.users.size)
        assertTrue(verdict.users.all { it.kind == TunerUserKind.STREAM })
        assertTrue(verdict.users.first().address == "192.168.1.21")
    }

    @Test
    fun aThirdViewerOnAnAlreadyTunedTransponderSharesTheTuner() {
        val snapshot = TunerSnapshot(
            twoTuners(),
            streams = listOf(
                StreamClient(npo1, "NPO 1", "192.168.1.21"),
                StreamClient(sbs6, "SBS6", "192.168.1.22"),
            ),
            inStandby = true,
        )
        assertEquals(TunerVerdict.Free(0, 2, sharedTransponder = true), TunerAvailability.evaluate(snapshot, npo2, me))
    }

    @Test
    fun twoStreamsOnOneTransponderUseOnlyOneTuner() {
        val snapshot = TunerSnapshot(
            twoTuners(),
            streams = listOf(
                StreamClient(npo1, "NPO 1", "192.168.1.21"),
                StreamClient(npo2, "NPO 2", "192.168.1.22"),
            ),
            inStandby = true,
        )
        assertEquals(TunerVerdict.Free(1, 2), TunerAvailability.evaluate(snapshot, rtl4, me))
    }

    @Test
    fun ownStreamDoesNotCountBecauseItIsReplaced() {
        val snapshot = TunerSnapshot(
            twoTuners(),
            streams = listOf(
                StreamClient(rtl4, "RTL 4", "::ffff:192.168.1.30"),
                StreamClient(sbs6, "SBS6", "192.168.1.22"),
            ),
            inStandby = true,
        )
        assertEquals(TunerVerdict.Free(1, 2), TunerAvailability.evaluate(snapshot, npo1, me))
    }

    @Test
    fun receiverThatIsOnUsesATunerForItsOwnPicture() {
        val snapshot = TunerSnapshot(
            twoTuners(TunerSlot(0, "Tuner A", "BCM45208 (DVB-S2)", live = "19.2°E / NPO 1")),
            streams = listOf(StreamClient(rtl4, "RTL 4", "192.168.1.21")),
            inStandby = false,
            liveReference = npo1,
        )
        val verdict = TunerAvailability.evaluate(snapshot, sbs6, me)
        assertTrue(verdict is TunerVerdict.Full)
        assertTrue((verdict as TunerVerdict.Full).users.any { it.kind == TunerUserKind.LIVE })
        // Same transponder as the receiver's own picture: allowed.
        assertTrue(TunerAvailability.evaluate(snapshot, npo2, me) is TunerVerdict.Free)
    }

    @Test
    fun liveServiceIsIgnoredInStandby() {
        val snapshot = TunerSnapshot(twoTuners(), inStandby = true, liveReference = npo1)
        assertEquals(TunerVerdict.Free(2, 2), TunerAvailability.evaluate(snapshot, rtl4, me))
    }

    @Test
    fun recordingTakesATuner() {
        val snapshot = TunerSnapshot(
            twoTuners(TunerSlot(0, "Tuner A", "BCM45208 (DVB-S2)", recording = "19.2°E / RTL 4")),
            streams = listOf(StreamClient(sbs6, "SBS6", "192.168.1.22")),
            inStandby = true,
            recordingReferences = listOf(rtl4),
        )
        val verdict = TunerAvailability.evaluate(snapshot, npo1, me) as TunerVerdict.Full
        assertTrue(verdict.users.any { it.kind == TunerUserKind.RECORDING && it.name.contains("RTL 4") })
        assertTrue(TunerAvailability.evaluate(snapshot, "1:0:19:7D6:3F0:1:C00000:0:0:0:", me) is TunerVerdict.Free)
    }

    @Test
    fun openPliListsStreamsAsRecordingsWithTheClientAddress() {
        // OpenPLi / E2OpenPlugins: a streaming tuner shows up under "rec" as "orbital / name (ip)".
        val snapshot = TunerSnapshot(
            twoTuners(
                TunerSlot(0, "Tuner A", "BCM45208 (DVB-S2)", recording = "19.2°E / RTL 4 (::ffff:192.168.1.30)"),
                TunerSlot(1, "Tuner B", "BCM45208 (DVB-S2)", recording = "19.2°E / SBS6 (::ffff:192.168.1.22)"),
            ),
            streams = listOf(
                StreamClient(rtl4, "RTL 4", "::ffff:192.168.1.30"),
                StreamClient(sbs6, "SBS6", "::ffff:192.168.1.22"),
            ),
            inStandby = true,
        )
        // Tuner A carries this device's own stream, which is about to be replaced.
        assertEquals(TunerVerdict.Free(1, 2), TunerAvailability.evaluate(snapshot, npo1, me))
        val other = TunerAvailability.evaluate(snapshot, npo1, setOf("192.168.1.40")) as TunerVerdict.Full
        assertEquals(2, other.users.size)
        assertTrue(other.users.all { it.kind == TunerUserKind.STREAM })
    }

    @Test
    fun oeAllianceTunerNumbersAttributeStreams() {
        val snapshot = TunerSnapshot(
            twoTuners(
                TunerSlot(0, "Tuner A", "BCM45208 (DVB-S2)", stream = "RTL 4"),
                TunerSlot(1, "Tuner B", "BCM45208 (DVB-S2)", stream = "SBS6"),
            ),
            streams = listOf(
                StreamClient(rtl4, "RTL 4", "192.168.1.30", tunerNumber = 0),
                StreamClient(sbs6, "SBS6", "192.168.1.22", tunerNumber = 1),
            ),
            inStandby = true,
        )
        assertEquals(TunerVerdict.Free(1, 2), TunerAvailability.evaluate(snapshot, npo1, me))
    }

    @Test
    fun onlyTunersForTheRightDeliverySystemCount() {
        val tuners = listOf(
            TunerSlot(0, "Tuner A", "BCM45208 (DVB-S2)"),
            TunerSlot(1, "Tuner B", "Si2169 (DVB-T2/C)"),
        )
        val snapshot = TunerSnapshot(
            tuners,
            streams = listOf(StreamClient(rtl4, "RTL 4", "192.168.1.21")),
            inStandby = true,
        )
        val verdict = TunerAvailability.evaluate(snapshot, npo1, me) as TunerVerdict.Full
        assertEquals(1, verdict.total)
        val cable = "1:0:19:1:2:3:FFFF0000:0:0:0:"
        assertEquals(TunerVerdict.Free(1, 1), TunerAvailability.evaluate(snapshot, cable, me))
    }

    @Test
    fun iptvNeedsNoTunerAndMissingTunerListIsUnknown() {
        val full = TunerSnapshot(
            twoTuners(),
            streams = listOf(StreamClient(rtl4, "RTL 4", "1.1.1.1"), StreamClient(sbs6, "SBS6", "1.1.1.2")),
        )
        assertEquals(TunerVerdict.NotNeeded, TunerAvailability.evaluate(full, "4097:0:1:0:0:0:0:0:0:0:http%3a//x:IPTV", me))
        assertEquals(TunerVerdict.Unknown, TunerAvailability.evaluate(TunerSnapshot(emptyList()), npo1, me))
    }

    @Test
    fun aRefusedStreamReportsAllMatchingTunersAsUsed() {
        val snapshot = TunerSnapshot(
            twoTuners(),
            streams = listOf(StreamClient(rtl4, "RTL 4", "192.168.1.21")),
            inStandby = true,
        )
        val refused = TunerAvailability.refused(snapshot, npo1, me)
        assertEquals(2, refused.used)
        assertEquals(2, refused.total)
        assertEquals(1, refused.users.size)
        assertEquals(0, TunerAvailability.refused(null, npo1, me).total)
    }
}
