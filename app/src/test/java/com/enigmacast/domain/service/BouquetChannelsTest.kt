package com.enigmacast.domain.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BouquetChannelsTest {
    @Test
    fun keepsChannelsAndDropsMarkers() {
        assertTrue(BouquetChannels.isPlayableChannel("1:0:19:1:0:0:0:0:0:0:"))
        assertFalse(BouquetChannels.isPlayableChannel("1:64:0:0:0:0:0:0:0:0::"))
        assertFalse(BouquetChannels.isPlayableChannel("1:7:1:0:0:0:0:0:0:0:FROM BOUQUET \"fav\""))
        assertFalse(BouquetChannels.isPlayableChannel("1:0:21E:0:0:0:0:0:0:0:https3a//cdn/a.m3u8:+++Friesland+++"))
        assertTrue(BouquetChannels.isPlayableChannel("4097:0:1:0:0:0:0:0:0:0:https3a//cdn/a.m3u8:RTV"))
        assertTrue(BouquetChannels.isSectionLabel("+++Friesland+++"))
        assertFalse(BouquetChannels.isSectionLabel("RTV Zulthe (Westerkwartier)"))
    }

    @Test
    fun televisionBouquetsAreTypeSevenFieldOne() {
        assertTrue(BouquetChannels.isTelevisionBouquet("1:7:1:0:0:0:0:0:0:0:FROM BOUQUET \"fav\" ORDER BY bouquet"))
        assertFalse(BouquetChannels.isTelevisionBouquet("1:7:2:0:0:0:0:0:0:0:FROM BOUQUET \"radio\" ORDER BY bouquet"))
        assertTrue(BouquetChannels.isRadioBouquet("1:7:2:0:0:0:0:0:0:0:FROM BOUQUET \"radio\" ORDER BY bouquet"))
        assertFalse(BouquetChannels.isRadioBouquet("1:7:1:0:0:0:0:0:0:0:FROM BOUQUET \"fav\" ORDER BY bouquet"))
    }

    @Test
    fun matchesServiceIdentityIgnoringTheNameSuffix() {
        assertTrue(
            BouquetChannels.sameService(
                "1:0:19:1:2:3:4:0:0:0:",
                "1:0:19:1:2:3:4:0:0:0:NPO3",
            ),
        )
        assertFalse(
            BouquetChannels.sameService(
                "1:0:19:1:2:3:4:0:0:0:",
                "1:0:19:9:2:3:4:0:0:0:",
            ),
        )
    }

    @Test
    fun internetStreamsWithTheSameServiceFieldsStayDistinct() {
        val prefix = "4097:0:1:1641:CDCB:EC:0:0:0:0:"
        assertFalse(
            BouquetChannels.sameService(
                "${prefix}https3a//cdn.example/dmax.m3u8:DMax (Italia)",
                "${prefix}https3a//cdn.example/nove.m3u8:Nove",
            ),
        )
        assertTrue(
            BouquetChannels.sameService(
                "${prefix}https3a//cdn.example/nove.m3u8:Nove",
                "${prefix}https%3a//cdn.example/nove.m3u8:NOVE",
            ),
        )
    }
}
