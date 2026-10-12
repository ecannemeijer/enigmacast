package com.enigmacast.domain.tuner

import com.enigmacast.domain.model.BouquetEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TunerSatellitesTest {
    @Test
    fun simpleDiseqcListsTheSlotsInOrder() {
        val settings = """
            config.Nims.0.configMode=simple
            config.Nims.0.diseqcMode=diseqc_a_b_c_d
            config.Nims.0.diseqcA=192
            config.Nims.0.diseqcB=130
            config.Nims.0.diseqcC=235
            config.Nims.0.diseqcD=282
        """.trimIndent()
        assertEquals(listOf(192, 130, 235, 282), TunerSatellites.positions(settings).map { it.tenths })
    }

    @Test
    fun advancedModeIgnoresTheSimpleSlot() {
        val settings = """
            config.Nims.0.configMode=advanced
            config.Nims.0.diseqcA=192
            config.Nims.0.advanced.sat.235.lnb=1
            config.Nims.0.advanced.sat.130.lnb=2
        """.trimIndent()
        assertEquals(listOf(235, 130), TunerSatellites.positions(settings).map { it.tenths })
        assertEquals("13.0°E · Hot Bird", TunerSatellites.label(TunerSatellites.positions(settings)[1]))
    }

    @Test
    fun nothingConnectedAndALinkedTunerAreSkipped() {
        val settings = """
            config.Nims.0.configMode=simple
            config.Nims.0.diseqcMode=single
            config.Nims.0.diseqcA=192
            config.Nims.1.configMode=nothing
            config.Nims.1.diseqcA=130
            config.Nims.2.configMode=equal
            config.Nims.2.connectedTo=0
            config.Nims.2.diseqcA=235
        """.trimIndent()
        assertEquals(listOf(192), TunerSatellites.positions(settings).map { it.tenths })
    }

    @Test
    fun eachTunerKeepsItsOwnSatellites() {
        val settings = """
            config.Nims.0.configMode=simple
            config.Nims.0.diseqcMode=diseqc_a_b
            config.Nims.0.diseqcA=192
            config.Nims.0.diseqcB=235
            config.Nims.1.configMode=simple
            config.Nims.1.diseqcMode=single
            config.Nims.1.diseqcA=282
            config.Nims.2.configMode=nothing
            config.Nims.2.diseqcA=130
        """.trimIndent()
        val tuners = TunerSatellites.tuners(settings)
        assertEquals(listOf(0, 1), tuners.map { it.index })
        assertEquals(listOf(192, 235), tuners[0].positions.map { it.tenths })
        assertEquals(listOf(282), tuners[1].positions.map { it.tenths })
    }

    @Test
    fun openAtvStoresDiseqcUnderTheDvbsKey() {
        val settings = """
            config.Nims.0.dvbs.diseqcA=192
            config.Nims.0.dvbs.diseqcB=235
            config.Nims.0.dvbs.diseqcC=282
            config.Nims.0.dvbs.diseqcD=130
            config.Nims.0.dvbs.diseqcMode=diseqc_a_b_c_d
            config.Nims.1.dvbs.configMode=nothing
            config.Nims.1.dvbs.diseqcA=192
        """.trimIndent()
        val tuners = TunerSatellites.tuners(settings)
        assertEquals(listOf(0), tuners.map { it.index })
        assertEquals(listOf(192, 235, 282, 130), tuners[0].positions.map { it.tenths })
    }

    @Test
    fun zeroAndOutOfRangePositionsAreDropped() {
        val settings = """
            config.Nims.0.configMode=simple
            config.Nims.0.diseqcMode=diseqc_a_b
            config.Nims.0.diseqcA=0
            config.Nims.0.diseqcB=9999
        """.trimIndent()
        assertEquals(emptyList<Int>(), TunerSatellites.positions(settings).map { it.tenths })
    }

    @Test
    fun mergeUsesTheTunerListAndTheFirstChannelOnEachSatellite() {
        val configured = TunerSatellites.positions(
            """
            config.Nims.0.configMode=simple
            config.Nims.0.diseqcMode=diseqc_a_b
            config.Nims.0.diseqcA=192
            config.Nims.0.diseqcB=130
            """.trimIndent(),
        )
        val channels = listOf(
            BouquetEntry("Astra", "1:0:1:1:1:1:C00000:0:0:0:"),
            BouquetEntry("Later Astra", "1:0:1:2:1:1:C00000:0:0:0:"),
            BouquetEntry("Hotbird", "1:0:1:3:1:1:820000:0:0:0:"),
            BouquetEntry("Stream", "4097:0:1:0:0:0:0:0:0:0:https%3a//example/live:Stream"),
        )
        val merged = TunerSatellites.merge(configured, channels)
        assertEquals(listOf(192, 130), merged.map { it.orbital.tenths })
        assertEquals("Astra", merged[0].channelName)
        assertEquals("1:0:1:1:1:1:C00000:0:0:0:", merged[0].serviceReference)
        assertEquals("Hotbird", merged[1].channelName)
    }

    @Test
    fun withoutTunerConfigTheChannelListIsTheSatelliteList() {
        val channels = listOf(
            BouquetEntry("Hotbird", "1:0:1:3:1:1:820000:0:0:0:"),
            BouquetEntry("Astra", "1:0:1:1:1:1:C00000:0:0:0:"),
        )
        val merged = TunerSatellites.merge(emptyList(), channels)
        assertEquals(listOf(130, 192), merged.map { it.orbital.tenths })
    }

    @Test
    fun aConfiguredSatelliteWithoutAChannelCannotBeTuned() {
        val configured = TunerSatellites.positions(
            """
            config.Nims.0.configMode=simple
            config.Nims.0.diseqcMode=single
            config.Nims.0.diseqcA=235
            """.trimIndent(),
        )
        val merged = TunerSatellites.merge(configured, emptyList())
        assertEquals("23.5°E · Astra 3", merged.single().label)
        assertNull(merged.single().serviceReference)
    }
}
