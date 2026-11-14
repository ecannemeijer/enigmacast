package com.enigmacast.domain.xtream

import org.junit.Assert.assertEquals
import org.junit.Test

class IptvGuideActionTest {
    private val now = 1_700_000_000L
    private val live = XtreamItem("1", "NPO 1", XtreamSection.LIVE, "g", archiveDays = 7)
    private val noArchive = XtreamItem("2", "NPO 2", XtreamSection.LIVE, "g")

    @Test
    fun currentProgrammeWatchesLive() {
        val event = XtreamEpg("Now", now - 600, now + 600)
        assertEquals(IptvGuideOk.WATCH_LIVE, IptvGuideAction.decide(live, event, now))
    }

    @Test
    fun pastProgrammeWithCatchupPlaysArchive() {
        val event = XtreamEpg("Yesterday", now - 3600, now - 1800)
        assertEquals(IptvGuideOk.PLAY_CATCHUP, IptvGuideAction.decide(live, event, now))
        assertEquals(IptvGuideOk.NONE, IptvGuideAction.decide(noArchive, event, now))
    }

    @Test
    fun futureProgrammeOffersAPrompt() {
        val event = XtreamEpg("Later", now + 1800, now + 3600)
        assertEquals(IptvGuideOk.PROMPT_FUTURE, IptvGuideAction.decide(live, event, now))
    }

    @Test
    fun missingCellDoesNothing() {
        assertEquals(IptvGuideOk.NONE, IptvGuideAction.decide(null, null, now))
    }

    @Test
    fun channelWithoutEpgWatchesLive() {
        assertEquals(IptvGuideOk.WATCH_LIVE, IptvGuideAction.decide(live, null, now))
        assertEquals(IptvGuideOk.WATCH_LIVE, IptvGuideAction.decide(noArchive, null, now))
    }
}
