package com.enigmacast.domain.xtream

import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.player.QuickOption
import com.enigmacast.domain.player.QuickOptions
import com.enigmacast.ui.osd.OsdInfoPresenter
import com.enigmacast.ui.xtream.IptvOsdModel
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvOsdTest {
    @Test fun satelliteSlotsAreEmpty() {
        assertTrue(OsdInfoPresenter.present(IptvOsdModel.INFO, IptvOsdModel.FIELDS).isEmpty)
    }

    @Test fun receiverOptionsAreHidden() {
        // Record (box timer) and quality (receiver transcoding) belong to the receiver; the guide
        // and recent channels are the IPTV list, so they only show for live.
        val live = QuickOptions.iptvTiles(vod = false)
        val vod = QuickOptions.iptvTiles(vod = true)
        listOf(QuickOption.RECORD, QuickOption.QUALITY).forEach { assertFalse(it in live); assertFalse(it in vod) }
        listOf(QuickOption.GUIDE, QuickOption.RECENT, QuickOption.PIP).forEach { assertTrue(it in live); assertFalse(it in vod) }
        assertTrue(QuickOption.RESTART in vod)
    }

    @Test fun shortEpgBecomesGuide() {
        val g = IptvOsdModel.guide(BouquetEntry("Ch", "iptv:1"), listOf(XtreamEpg("Next", 2000, 3000), XtreamEpg("Now", 1000, 2000)), nowSec = 1500, zone = ZoneOffset.UTC)!!
        assertEquals("Now", g.currentEvent?.title)
        assertEquals("00:16", g.events.first().beginLabel)
    }
}
