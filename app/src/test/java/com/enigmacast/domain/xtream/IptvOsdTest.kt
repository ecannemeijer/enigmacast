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
        val live = QuickOptions.iptvTiles(vod = false)
        listOf(QuickOption.RECORD, QuickOption.GUIDE, QuickOption.SEARCH, QuickOption.QUALITY, QuickOption.RECENT).forEach { assertFalse(it in live) }
        assertFalse(QuickOption.RESTART in live)
        assertTrue(QuickOption.RESTART in QuickOptions.iptvTiles(vod = true))
    }

    @Test fun shortEpgBecomesGuide() {
        val g = IptvOsdModel.guide(BouquetEntry("Ch", "iptv:1"), listOf(XtreamEpg("Next", 2000, 3000), XtreamEpg("Now", 1000, 2000)), nowSec = 1500, zone = ZoneOffset.UTC)!!
        assertEquals("Now", g.currentEvent?.title)
        assertEquals("00:16", g.events.first().beginLabel)
    }
}
