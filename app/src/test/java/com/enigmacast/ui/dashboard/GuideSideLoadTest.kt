package com.enigmacast.ui.dashboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideSideLoadTest {
    @Test
    fun bouquetListLoadsWhenChannelsCameFromTheStream() {
        // Regression: the own-tuner stream filled the channels first and the guide never fetched its bouquets.
        assertTrue(GuideSideLoad.needsBouquetList(bouquetCount = 0, loading = false))
        assertFalse(GuideSideLoad.needsChannels(channelsLoaded = true))
    }

    @Test
    fun noSecondFetch() {
        assertFalse(GuideSideLoad.needsBouquetList(bouquetCount = 12, loading = false))
        assertFalse(GuideSideLoad.needsBouquetList(bouquetCount = 0, loading = true))
        assertTrue(GuideSideLoad.needsChannels(channelsLoaded = false))
    }
}
