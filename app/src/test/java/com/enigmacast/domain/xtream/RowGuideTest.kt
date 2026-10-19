package com.enigmacast.domain.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RowGuideTest {
    private val nowMs = 1_800_000_000_000L
    private val now = nowMs / 1000
    private val onAir = XtreamEpg("News", now - 600, now + 1200, "Bulletin \"live\"\nline 2")
    private val next = XtreamEpg("Film", now + 1200, now + 7200)

    @Test
    fun neverLookedUpRowsNeedAFetch() {
        assertTrue(RowGuide.needsFetch(null, null, null, nowMs))
    }

    @Test
    fun aFailureIsRetriedAfterAMinuteNotRememberedAsEmpty() {
        assertFalse(RowGuide.needsFetch(null, null, nowMs - 30_000, nowMs))
        assertTrue(RowGuide.needsFetch(null, null, nowMs - RowGuide.FAIL_RETRY_MS, nowMs))
    }

    @Test
    fun freshListingsWithAProgrammeOnNowAreKeptUntilTheTtl() {
        assertFalse(RowGuide.needsFetch(nowMs - 60_000, listOf(onAir, next), null, nowMs))
        assertTrue(RowGuide.needsFetch(nowMs - RowGuide.TTL_MS, listOf(onAir, next), null, nowMs))
    }

    @Test
    fun anEmptyAnswerIsAskedAgainLater() {
        assertFalse(RowGuide.needsFetch(nowMs - 60_000, emptyList(), null, nowMs))
        assertTrue(RowGuide.needsFetch(nowMs - RowGuide.EMPTY_RETRY_MS, emptyList(), null, nowMs))
    }

    @Test
    fun trimKeepsNowAndTheComingDayOnly() {
        val past = XtreamEpg("Old", now - 7200, now - 600)
        val far = XtreamEpg("Far", now + 2 * 86_400, now + 2 * 86_400 + 600)
        assertEquals(listOf(onAir, next), RowGuide.trim(listOf(next, past, far, onAir), now))
    }

    @Test
    fun storedListingsReadBackTheSame() {
        val list = listOf(onAir, next)
        assertEquals(list, RowGuide.decode(RowGuide.encode(list)))
        assertEquals(emptyList<XtreamEpg>(), RowGuide.decode(RowGuide.encode(emptyList())))
        assertNull(RowGuide.decode("not json"))
    }
}
