package com.enigmacast.domain.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class FallbackEpgXmltvTest {
    private val sample = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="Cartoonito.nl">
            <display-name>NL - Cartoonito</display-name>
          </channel>
          <channel id="NPO1.nl">
            <display-name>NPO 1 HD</display-name>
          </channel>
          <channel id="BBC1.uk">
            <display-name>BBC One</display-name>
          </channel>
          <programme start="20261113001500 +0000" stop="20261113004500 +0000" channel="Cartoonito.nl">
            <title>Paw Patrol</title>
            <desc>The pups save the day.</desc>
          </programme>
          <programme start="20261113010000 +0100" stop="20261113020000 +0100" channel="NPO1.nl">
            <title>Journaal</title>
          </programme>
          <programme start="20261113120000 +0000" stop="20261113130000 +0000" channel="BBC1.uk">
            <title>News</title>
          </programme>
        </tv>
    """.trimIndent()

    @Test
    fun xmltvTimesIncludeTheOffset() {
        assertEquals(1_794_528_900L, FallbackEpgXmltv.parseTime("20261113001500 +0000"))
        // 01:00 +0100 is 00:00 UTC on the same morning.
        assertEquals(1_794_528_000L, FallbackEpgXmltv.parseTime("20261113010000 +0100"))
        assertEquals(FallbackEpgXmltv.parseTime("20261113001500 +0000"), FallbackEpgXmltv.parseTime("20261113001500+0000"))
    }

    @Test
    fun onlyWantedChannelsAreKeptAndMatchedByNameOrTvgId() {
        val byName = FallbackWanted(nameKeys = setOf(FallbackEpgNames.key("Cartoonito HD")))
        val kept = ArrayList<FallbackProgramme>()
        val count = FallbackEpgXmltv.parse(ByteArrayInputStream(sample.toByteArray()), byName, kept::add)
        assertEquals(1, count)
        assertEquals("Cartoonito.nl", kept.single().xmltvId)
        assertEquals("Paw Patrol", kept.single().title)
        assertEquals("The pups save the day.", kept.single().description)
        assertEquals(1_794_528_900L, kept.single().beginSec)
        assertEquals(1_794_530_700L, kept.single().endSec)

        val byTvg = FallbackWanted(nameKeys = emptyList(), tvgIds = listOf("Cartoonito.nl"))
        val fromId = ArrayList<FallbackProgramme>()
        FallbackEpgXmltv.parse(ByteArrayInputStream(sample.toByteArray()), byTvg, fromId::add)
        assertEquals("Paw Patrol", fromId.single().title)

        val npo = FallbackWanted(nameKeys = setOf(FallbackEpgNames.key("NPO 1")))
        val fromNpo = ArrayList<FallbackProgramme>()
        FallbackEpgXmltv.parse(ByteArrayInputStream(sample.toByteArray()), npo, fromNpo::add)
        assertEquals("Journaal", fromNpo.single().title)
        // 01:00+0100 → 00:00 UTC
        assertEquals(1_794_528_000L, fromNpo.single().beginSec)
    }

    @Test
    fun nothingIsKeptWhenNothingIsWanted() {
        val wanted = FallbackWanted(nameKeys = setOf(FallbackEpgNames.key("A channel that is not there")))
        val kept = ArrayList<FallbackProgramme>()
        val count = FallbackEpgXmltv.parse(ByteArrayInputStream(sample.toByteArray()), wanted, kept::add)
        assertEquals(0, count)
        assertTrue(kept.isEmpty())
    }
}
