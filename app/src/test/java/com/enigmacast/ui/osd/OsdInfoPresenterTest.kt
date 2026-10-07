package com.enigmacast.ui.osd

import com.enigmacast.R
import com.enigmacast.domain.model.OsdInfoFields
import com.enigmacast.domain.osdinfo.CryptState
import com.enigmacast.domain.osdinfo.OsdInfoBuilder
import com.enigmacast.domain.osdinfo.OsdInfoInput
import com.enigmacast.domain.osdinfo.Polarisation
import com.enigmacast.domain.osdinfo.ReceiverCurrent
import com.enigmacast.domain.osdinfo.ReceiverOsdData
import com.enigmacast.domain.osdinfo.Scrambling
import com.enigmacast.domain.osdinfo.SignalInfo
import com.enigmacast.domain.osdinfo.SignalReading
import com.enigmacast.domain.osdinfo.StreamClientInfo
import com.enigmacast.domain.osdinfo.Transponder
import com.enigmacast.domain.osdinfo.TsStreamFacts
import com.enigmacast.util.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OsdInfoPresenterTest {
    private val astra3 = "1:0:1:1:1:1:EB0000:0:0:0:"
    private val astra1 = "1:0:1:1:1:1:C00000:0:0:0:"
    private val hotbird = "1:0:1:1:1:1:820000:0:0:0:"

    @Test
    fun satellitePositionsAreNamed() {
        assertEquals("23.5°E · Astra 3", satelliteOf(astra3))
        assertEquals("19.2°E · Astra 1", satelliteOf(astra1))
        assertEquals("13.0°E · Hot Bird", satelliteOf(hotbird))
    }

    @Test
    fun signalStrengthAndQualityComeFromTheReading() {
        val view = present(
            OsdInfoInput(
                reference = astra1,
                receiver = ReceiverOsdData(
                    signal = SignalReading(agcPercent = 70, snrPercent = 84, tunerNumber = 0),
                    current = ReceiverCurrent(reference = astra1),
                ),
            ),
        )
        assertEquals(70, view.signal?.strengthPercent)
        assertEquals(84, view.signal?.qualityPercent)
        assertNull(view.signal?.qualityDb)
    }

    @Test
    fun qualityFallsBackToDecibels() {
        val view = present(
            OsdInfoInput(
                reference = astra1,
                receiver = ReceiverOsdData(
                    signal = SignalReading(agcPercent = 61, snrDb = 12.34f, tunerNumber = 0),
                    current = ReceiverCurrent(reference = astra1),
                ),
            ),
        )
        assertEquals(61, view.signal?.strengthPercent)
        assertNull(view.signal?.qualityPercent)
        assertEquals("12.3 dB", view.signal?.qualityDb)
    }

    @Test
    fun anotherTunersSignalIsNotShown() {
        val view = present(
            OsdInfoInput(
                reference = astra1,
                receiver = ReceiverOsdData(
                    signal = SignalReading(agcPercent = 90, snrPercent = 90, tunerNumber = 0),
                    current = ReceiverCurrent(reference = astra3),
                ),
            ),
        )
        assertNull(view.signal)
    }

    @Test
    fun aClearChannelIsFreeAndAScrambledOneIsNotDecrypted() {
        val free = present(
            OsdInfoInput(
                reference = astra1,
                stream = TsStreamFacts(pmtSeen = true, scrambling = Scrambling.CLEAR),
            ),
        )
        assertEquals(UiText.res(R.string.osd_crypt_free), free.crypt?.text)
        assertEquals(CryptTone.FREE, free.crypt?.tone)

        val locked = present(
            OsdInfoInput(
                reference = astra1,
                stream = TsStreamFacts(pmtSeen = true, caids = listOf(0x1802), scrambling = Scrambling.SCRAMBLED),
            ),
        )
        assertEquals(CryptState.NOT_DECRYPTED, OsdInfoBuilder.build(
            OsdInfoInput(
                reference = astra1,
                stream = TsStreamFacts(pmtSeen = true, caids = listOf(0x1802), scrambling = Scrambling.SCRAMBLED),
            ),
        ).crypt.state)
        assertEquals(UiText.res(R.string.osd_crypt_not_decrypted), locked.crypt?.text)
        assertEquals(CryptTone.PROBLEM, locked.crypt?.tone)
        assertEquals("Nagravision", locked.crypt?.systems)
    }

    @Test
    fun anEncryptedChannelThatArrivesClearIsDecrypted() {
        val view = present(
            OsdInfoInput(
                reference = astra1,
                stream = TsStreamFacts(pmtSeen = true, caids = listOf(0x1802), scrambling = Scrambling.CLEAR),
            ),
        )
        assertEquals(UiText.res(R.string.osd_crypt_decrypted), view.crypt?.text)
        assertEquals(CryptTone.OPEN, view.crypt?.tone)
    }

    @Test
    fun switchesHideSlotsTheSkinWouldOtherwiseDraw() {
        val view = OsdInfoPresenter.present(
            OsdInfoBuilder.build(
                OsdInfoInput(
                    reference = astra1,
                    receiver = ReceiverOsdData(
                        signal = SignalReading(agcPercent = 70, snrPercent = 80),
                        current = ReceiverCurrent(reference = astra1),
                        streams = listOf(StreamClientInfo(astra1, "10.0.0.8", tunerNumber = 0, transponder = Transponder(system = "DVB-S2"))),
                    ),
                    stream = TsStreamFacts(pmtSeen = true, scrambling = Scrambling.CLEAR),
                ),
            ),
            OsdInfoFields(signal = false, satellite = false, encryption = false, tuner = false, technical = false),
        )
        assertTrue(view.isEmpty)
    }

    @Test
    fun tunerAndTransponderStayOutOfTheSignalLine() {
        val built = OsdInfoBuilder.build(
            OsdInfoInput(
                reference = astra1,
                receiver = ReceiverOsdData(
                    streams = listOf(
                        StreamClientInfo(
                            astra1,
                            "10.0.0.8",
                            tunerNumber = 0,
                            transponder = Transponder(frequencyKhz = 11_494_000, polarisation = Polarisation.HORIZONTAL, symbolRate = 22_000_000, system = "DVB-S2"),
                        ),
                    ),
                ),
            ),
        )
        val hidden = OsdInfoPresenter.present(built, OsdInfoFields())
        assertEquals(UiText.res(R.string.osd_tuner_type, "A", "DVB-S2"), hidden.tuner)
        assertNull(hidden.technical)
        val shown = OsdInfoPresenter.present(built, OsdInfoFields(technical = true))
        assertEquals("11494 H 22000 · DVB-S2", shown.technical)
    }

    private fun satelliteOf(reference: String): String? =
        present(OsdInfoInput(reference = reference)).satellite

    private fun present(input: OsdInfoInput) =
        OsdInfoPresenter.present(OsdInfoBuilder.build(input), OsdInfoFields())
}
