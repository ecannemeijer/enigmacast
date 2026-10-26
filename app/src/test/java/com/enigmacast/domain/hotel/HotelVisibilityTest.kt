package com.enigmacast.domain.hotel

import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.xtream.XtreamCategory
import com.enigmacast.domain.xtream.XtreamSection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hotel mode never leaves guests without channels: empty or stale whitelists show everything. */
class HotelVisibilityTest {
    private val fav = "1:7:1:0:0:0:0:0:0:0:FROM BOUQUET \"userbouquet.favourites.tv\" ORDER BY bouquet"
    private val sport = "1:7:1:0:0:0:0:0:0:0:FROM BOUQUET \"userbouquet.sport.tv\" ORDER BY bouquet"
    private val adult = "1:7:1:0:0:0:0:0:0:0:FROM BOUQUET \"userbouquet.adult.tv\" ORDER BY bouquet"
    private val list = listOf(BouquetEntry("Favourites", fav), BouquetEntry("Sport", sport), BouquetEntry("Adult", adult))
    private val cats = listOf(XtreamCategory("1", "News"), XtreamCategory("2", "Sport"))

    @After fun reset() {
        HotelRuntime.set(HotelConfig())
        HotelRuntime.bouquetWhitelistUnmatched(null)
    }

    @Test fun emptyBouquetWhitelistShowsAll() {
        val config = HotelConfig(enabled = true, allowedBouquets = emptySet())
        assertEquals(list, HotelFilter.bouquets(list, config))
        assertFalse(HotelFilter.restrictsBouquets(config))
        assertTrue(HotelFilter.bouquetAllowed(adult, config))
    }

    @Test fun staleBouquetWhitelistFallsBackToAll() {
        val config = HotelConfig(enabled = true, allowedBouquets = setOf("1:7:1:0:0:0:0:0:0:0:FROM BOUQUET \"userbouquet.old.tv\" ORDER BY bouquet"))
        val visible = HotelFilter.visibleBouquets(list, config)
        assertTrue(visible.fellBack)
        assertEquals(list, visible.items)
        assertEquals(1, visible.whitelist)
        assertEquals(3, visible.total)
    }

    @Test fun matchingWhitelistStillFilters() {
        val config = HotelConfig(enabled = true, allowedBouquets = setOf(fav))
        val visible = HotelFilter.visibleBouquets(list, config)
        assertFalse(visible.fellBack)
        assertEquals(listOf(fav), visible.items.map { it.reference })
    }

    @Test fun referenceSpellingDoesNotMatter() {
        // Cache and receiver spell the same bouquet differently (spaces, case, quotes kept).
        val config = HotelConfig(enabled = true, allowedBouquets = setOf("1:7:1:0:0:0:0:0:0:0:FROM BOUQUET \"userbouquet.Sport.tv\" ORDER BY bouquet "))
        assertEquals(listOf(sport), HotelFilter.bouquets(list, config).map { it.reference })
        assertTrue(HotelFilter.bouquetAllowed("1:7:1:0:0:0:0:0:0:0:FROM BOUQUET \"userbouquet.sport.tv\"  ORDER BY bouquet", config))
        assertEquals("userbouquet.sport.tv", HotelFilter.bouquetKey(sport))
        assertEquals("fav", HotelFilter.bouquetKey(" FAV "))
    }

    @Test fun emptyListStaysEmptyWithoutFallbackFlag() {
        val visible = HotelFilter.visibleBouquets(emptyList(), HotelConfig(enabled = true, allowedBouquets = setOf(fav)))
        assertTrue(visible.items.isEmpty())
        assertFalse(visible.fellBack)
    }

    @Test fun guestConfigDropsAnUnmatchedWhitelist() {
        val whitelist = setOf("old")
        HotelRuntime.set(HotelConfig(enabled = true, allowedBouquets = whitelist))
        assertEquals(whitelist, HotelRuntime.guestConfig.allowedBouquets)
        HotelRuntime.bouquetWhitelistUnmatched(whitelist)
        assertNull(HotelRuntime.guestConfig.allowedBouquets)
        // The owner picks again: the new whitelist applies.
        HotelRuntime.set(HotelConfig(enabled = true, allowedBouquets = setOf(fav)))
        assertEquals(setOf(fav), HotelRuntime.guestConfig.allowedBouquets)
    }

    @Test fun categoryWhitelistMatchingNothingShowsAll() {
        val config = HotelFilter.withCategories(HotelConfig(enabled = true, source = HotelSource.IPTV), "xtream:a", XtreamSection.LIVE, setOf("99"))
        val visible = HotelFilter.visibleCategories(cats, XtreamSection.LIVE, config) { "xtream:a" }
        assertTrue(visible.fellBack)
        assertEquals(cats, visible.items)
        assertEquals(cats, HotelFilter.categories(cats, "xtream:a", XtreamSection.LIVE, config))
    }

    @Test fun emptyCategoryWhitelistShowsAll() {
        val config = HotelFilter.withCategories(HotelConfig(enabled = true, source = HotelSource.IPTV), "xtream:a", XtreamSection.LIVE, emptySet())
        assertEquals(cats, HotelFilter.categories(cats, "xtream:a", XtreamSection.LIVE, config))
    }

    @Test fun categoryIdsMatchTrimmed() {
        val config = HotelFilter.withCategories(HotelConfig(enabled = true, source = HotelSource.IPTV), "xtream:a", XtreamSection.LIVE, setOf(" 2"))
        assertEquals(listOf("2"), HotelFilter.categories(cats, "xtream:a", XtreamSection.LIVE, config).map { it.id })
    }

    @Test fun vodStaysHiddenWithoutVod() {
        val config = HotelConfig(enabled = true, source = HotelSource.IPTV, iptvVod = false)
        assertTrue(HotelFilter.visibleCategories(cats, XtreamSection.MOVIES, config) { "x" }.items.isEmpty())
    }

    @Test fun logLineHasCountsOnly() {
        assertEquals(
            "hotel: bron=SAT profiel=12345678 whitelist=3 → zichtbaar=0 (van 12)",
            HotelVisibilityLog.line("SAT", "1234567890abcdef", 3, 0, 12, fellBack = false),
        )
        assertEquals(
            "hotel: bron=IPTV live profiel=- whitelist=2 → zichtbaar=5 (whitelist komt met niets overeen: alle 5 zichtbaar)",
            HotelVisibilityLog.line("IPTV", "", 2, 5, 5, fellBack = true, what = "live"),
        )
    }
}
