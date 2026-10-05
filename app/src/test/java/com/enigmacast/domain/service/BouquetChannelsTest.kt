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
    }

    @Test
    fun televisionBouquetsAreTypeSevenFieldOne() {
        assertTrue(BouquetChannels.isTelevisionBouquet("1:7:1:0:0:0:0:0:0:0:FROM BOUQUET \"fav\" ORDER BY bouquet"))
        assertFalse(BouquetChannels.isTelevisionBouquet("1:7:2:0:0:0:0:0:0:0:FROM BOUQUET \"radio\" ORDER BY bouquet"))
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
}
