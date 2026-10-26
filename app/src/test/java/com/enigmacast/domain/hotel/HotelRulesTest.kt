package com.enigmacast.domain.hotel

import com.enigmacast.domain.model.AppSettings
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.LastChannel
import com.enigmacast.domain.model.LastIptvChannel
import com.enigmacast.domain.model.LastLiveSource
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.player.LiveSubtitles
import com.enigmacast.domain.player.QuickOption
import com.enigmacast.domain.remote.PlayerAction
import com.enigmacast.domain.xtream.XtreamCategory
import com.enigmacast.domain.xtream.XtreamSection
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HotelMenuTest {
    @Test fun satelliteShowsOnlySatelliteAndSettings() {
        val config = HotelConfig(enabled = true, source = HotelSource.SATELLITE, iptvVod = true)
        assertEquals(listOf(HotelMenuItem.SAT_TV, HotelMenuItem.SETTINGS), HotelMenu.items(config))
    }

    @Test fun iptvWithoutVodShowsLiveAndSettings() {
        val config = HotelConfig(enabled = true, source = HotelSource.IPTV)
        assertEquals(listOf(HotelMenuItem.IPTV_LIVE, HotelMenuItem.SETTINGS), HotelMenu.items(config))
    }

    @Test fun iptvWithVodAddsFilmsAndSeriesSettingsLast() {
        val items = HotelMenu.items(HotelConfig(enabled = true, source = HotelSource.IPTV, iptvVod = true))
        assertEquals(
            listOf(HotelMenuItem.IPTV_LIVE, HotelMenuItem.IPTV_MOVIES, HotelMenuItem.IPTV_SERIES, HotelMenuItem.SETTINGS),
            items,
        )
        assertEquals(HotelMenuItem.SETTINGS, items.last())
    }

    @Test fun vodAllowedOnlyForIptv() {
        assertFalse(HotelConfig(source = HotelSource.SATELLITE, iptvVod = true).vodAllowed)
        assertTrue(HotelConfig(source = HotelSource.IPTV, iptvVod = true).vodAllowed)
    }
}

class HotelRoutesTest {
    private val sat = HotelConfig(enabled = true, source = HotelSource.SATELLITE)
    private val iptv = HotelConfig(enabled = true, source = HotelSource.IPTV, iptvVod = true)

    @Test fun offAllowsEverything() {
        HotelRoutes.Screen.entries.forEach { assertTrue(HotelRoutes.allowed(it, HotelConfig())) }
    }

    @Test fun otherScreensNeverReachable() {
        assertFalse(HotelRoutes.allowed(HotelRoutes.Screen.OTHER, sat))
        assertFalse(HotelRoutes.allowed(HotelRoutes.Screen.OTHER, iptv))
    }

    @Test fun onlyTheChosenSource() {
        assertTrue(HotelRoutes.allowed(HotelRoutes.Screen.SATELLITE, sat))
        assertFalse(HotelRoutes.allowed(HotelRoutes.Screen.IPTV, sat))
        assertTrue(HotelRoutes.allowed(HotelRoutes.Screen.IPTV, iptv))
        assertFalse(HotelRoutes.allowed(HotelRoutes.Screen.SATELLITE, iptv))
        assertTrue(HotelRoutes.allowed(HotelRoutes.Screen.HOTEL, iptv))
        assertTrue(HotelRoutes.allowed(HotelRoutes.Screen.LOADING, sat))
    }
}

class HotelPinTest {
    @Test fun lengthsFourToSix() {
        assertFalse(HotelPin.valid("123"))
        assertTrue(HotelPin.valid("1234"))
        assertTrue(HotelPin.valid("12345"))
        assertTrue(HotelPin.valid("123456"))
        assertFalse(HotelPin.valid("1234567"))
        assertFalse(HotelPin.valid("12a4"))
    }

    @Test fun hashIsSaltedAndNotPlain() {
        val a = HotelPin.hash("482915")
        val b = HotelPin.hash("482915")
        assertFalse(a.contains("482915"))
        assertTrue(a != b)
        assertTrue(HotelPin.verify("482915", a))
        assertTrue(HotelPin.verify("482915", b))
        assertFalse(HotelPin.verify("482916", a))
        assertFalse(HotelPin.verify("4829", a))
    }

    @Test fun fiveWrongTriesLockOneMinute() {
        val stored = HotelPin.hash("2468")
        var attempts = HotelPinAttempts()
        val now = 1_000_000L
        for (left in 4 downTo 1) {
            val (result, next) = attempts.check("1111", stored, now)
            assertEquals(com.enigmacast.domain.security.PinCheck.Wrong(left), result)
            attempts = next
        }
        val (fifth, locked) = attempts.check("1111", stored, now)
        assertEquals(com.enigmacast.domain.security.PinCheck.CoolingDown(60), fifth)
        // Even the right PIN waits during the lockout.
        assertEquals(com.enigmacast.domain.security.PinCheck.CoolingDown(30), locked.check("2468", stored, now + 30_000).first)
        val (ok, reset) = locked.check("2468", stored, now + 60_000)
        assertEquals(com.enigmacast.domain.security.PinCheck.Ok, ok)
        assertEquals(HotelPinAttempts(), reset)
    }

    @Test fun lockoutsGrowAndCap() {
        assertEquals(60_000L, HotelPinAttempts.lockMs(0))
        assertEquals(120_000L, HotelPinAttempts.lockMs(1))
        assertEquals(240_000L, HotelPinAttempts.lockMs(2))
        assertEquals(HotelPinAttempts.MAX_LOCK_MS, HotelPinAttempts.lockMs(9))
        var attempts = HotelPinAttempts()
        val stored = HotelPin.hash("2468")
        var now = 0L
        repeat(5) { attempts = attempts.check("0000", stored, now).second }
        assertEquals(60_000L, attempts.lockedUntilMs - now)
        now = attempts.lockedUntilMs
        repeat(5) { attempts = attempts.check("0000", stored, now).second }
        assertEquals(120_000L, attempts.lockedUntilMs - now)
    }

    @Test fun noStoredHashMeansOpen() {
        assertEquals(com.enigmacast.domain.security.PinCheck.Ok, HotelPinAttempts().check("9999", null, 0).first)
    }
}

class HotelFilterTest {
    private val cats = listOf(XtreamCategory("1", "News"), XtreamCategory("2", "Sport"), XtreamCategory("3", "Adult"))
    private val source = "xtream:acc"

    @Test fun offShowsEverything() {
        val config = HotelConfig(enabled = false, allowedCategories = mapOf(HotelFilter.categoryKey(source, XtreamSection.LIVE) to setOf("1")))
        assertEquals(cats, HotelFilter.categories(cats, source, XtreamSection.LIVE, config))
    }

    @Test fun missingKeyShowsAll() {
        val config = HotelConfig(enabled = true, source = HotelSource.IPTV)
        assertEquals(cats, HotelFilter.categories(cats, source, XtreamSection.LIVE, config))
    }

    @Test fun whitelistPerSourceAndSection() {
        var config = HotelConfig(enabled = true, source = HotelSource.IPTV, iptvVod = true)
        config = HotelFilter.withCategories(config, source, XtreamSection.LIVE, setOf("1", "2"))
        assertEquals(listOf("1", "2"), HotelFilter.categories(cats, source, XtreamSection.LIVE, config).map { it.id })
        // Another provider and another section are untouched.
        assertEquals(cats, HotelFilter.categories(cats, "provider:p2", XtreamSection.LIVE, config))
        assertEquals(cats, HotelFilter.categories(cats, source, XtreamSection.MOVIES, config))
        // A category the provider adds later stays hidden.
        val later = cats + XtreamCategory("4", "New")
        assertEquals(listOf("1", "2"), HotelFilter.categories(later, source, XtreamSection.LIVE, config).map { it.id })
        assertEquals(setOf("1", "2"), HotelFilter.shownCategories(later, source, XtreamSection.LIVE, config))
    }

    @Test fun vodSectionsHiddenWithoutVod() {
        val config = HotelConfig(enabled = true, source = HotelSource.IPTV, iptvVod = false)
        assertTrue(HotelFilter.categories(cats, source, XtreamSection.MOVIES, config).isEmpty())
        assertTrue(HotelFilter.categories(cats, source, XtreamSection.SERIES, config).isEmpty())
        assertEquals(cats, HotelFilter.categories(cats, source, XtreamSection.LIVE, config))
        // Satellite hotel: no IPTV at all.
        assertTrue(HotelFilter.categories(cats, source, XtreamSection.LIVE, config.copy(source = HotelSource.SATELLITE)).isEmpty())
    }

    @Test fun playlistCategoriesBySource() {
        val mixed = listOf(XtreamCategory("1", "A", providerId = "p1"), XtreamCategory("1", "A", providerId = "p2"))
        val config = HotelFilter.withCategories(HotelConfig(enabled = true, source = HotelSource.IPTV), "provider:p1", XtreamSection.LIVE, emptySet())
        val shown = HotelFilter.categoriesBySource(mixed, XtreamSection.LIVE, config) { "provider:${it.providerId}" }
        assertEquals(listOf("p2"), shown.map { it.providerId })
    }

    @Test fun bouquetWhitelist() {
        val list = listOf(BouquetEntry("Favourites", "fav"), BouquetEntry("Adult", "xxx"), BouquetEntry("Sport", "sport"))
        assertEquals(list, HotelFilter.bouquets(list, HotelConfig(enabled = true)))
        val config = HotelConfig(enabled = true, allowedBouquets = setOf("fav", "sport"))
        assertEquals(listOf("fav", "sport"), HotelFilter.bouquets(list, config).map { it.reference })
        assertTrue(HotelFilter.restrictsBouquets(config))
        assertFalse(HotelFilter.bouquetAllowed("xxx", config))
        assertTrue(HotelFilter.bouquetAllowed("xxx", config.copy(enabled = false)))
        assertFalse(HotelFilter.restrictsBouquets(config.copy(enabled = false)))
    }
}

class HotelRestrictionsTest {
    @Test fun recordingAndInfoTilesHidden() {
        val all = QuickOption.entries.toList()
        val shown = HotelRestrictions.tiles(all, HotelConfig(enabled = true))
        assertFalse(QuickOption.RECORD in shown)
        assertFalse(QuickOption.STREAM_INFO in shown)
        assertFalse(QuickOption.PIP in shown)
        assertEquals(all, HotelRestrictions.tiles(all, HotelConfig()))
    }

    @Test fun recordActionBlocked() {
        assertFalse(HotelRestrictions.actionAllowed(PlayerAction.RECORD, HotelConfig(enabled = true)))
        assertFalse(HotelRestrictions.actionAllowed(PlayerAction.SWITCH_RECEIVER, HotelConfig(enabled = true)))
        assertTrue(HotelRestrictions.actionAllowed(PlayerAction.CHANNEL_UP, HotelConfig(enabled = true)))
        assertTrue(HotelRestrictions.actionAllowed(PlayerAction.RECORD, HotelConfig()))
    }
}

class LauncherToggleTest {
    @Test fun aliasOnlyWithHotelOnLauncherAndPin() {
        val on = HotelConfig(enabled = true, launcher = true)
        assertTrue(LauncherToggle.aliasEnabled(on, pinSet = true))
        assertFalse(LauncherToggle.aliasEnabled(on, pinSet = false))
        assertFalse(LauncherToggle.aliasEnabled(on.copy(enabled = false), pinSet = true))
        assertFalse(LauncherToggle.aliasEnabled(on.copy(launcher = false), pinSet = true))
    }
}

class WifiQrTest {
    @Test fun escapesSpecialCharacters() {
        assertEquals("WIFI:T:WPA;S:B\\;B;P:pa\\:ss\\\\;;", WifiQr.payload("B;B", "pa:ss\\"))
        assertEquals("WIFI:T:nopass;S:Guest;;", WifiQr.payload("Guest", ""))
    }
}

class HotelScheduleTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private fun at(h: Int, m: Int, day: Int = 1) = LocalDateTime.of(2026, 10, day, h, m).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test fun dueOncePerDayAfterTheTime() {
        val config = HotelConfig(enabled = true, cleanupDaily = true, cleanupMinute = 11 * 60)
        assertFalse(HotelSchedule.due(config, at(10, 59), zone))
        assertTrue(HotelSchedule.due(config, at(11, 0), zone))
        val done = config.copy(lastCleanupDay = HotelSchedule.epochDay(at(11, 0), zone))
        assertFalse(HotelSchedule.due(done, at(18, 0), zone))
        assertTrue(HotelSchedule.due(done, at(11, 5, day = 2), zone))
        assertFalse(HotelSchedule.due(config.copy(enabled = false), at(12, 0), zone))
        assertFalse(HotelSchedule.due(config.copy(cleanupDaily = false), at(12, 0), zone))
    }

    @Test fun armingAfterTheTimeSkipsToday() {
        val config = HotelConfig(enabled = true, cleanupDaily = true, cleanupMinute = 11 * 60)
        assertFalse(HotelSchedule.due(HotelSchedule.armed(config, at(15, 0), zone), at(15, 0), zone))
        assertEquals(config, HotelSchedule.armed(config, at(9, 0), zone))
    }

    @Test fun stepsWrapAroundMidnight() {
        assertEquals(11 * 60 + 15, HotelSchedule.step(11 * 60, 1))
        assertEquals(23 * 60 + 45, HotelSchedule.step(0, -1))
        assertEquals("09:05", HotelSchedule.label(9 * 60 + 5))
    }
}

class HotelCleanupPlanTest {
    private val receiver = ReceiverConfig(id = "vu")
    private val start = LastChannel(reference = "1:0:1:NPO1", name = "NPO 1", bouquetReference = "fav")

    @Test fun clearsGuestTracesAndSetsStartChannel() {
        val before = AppSettings(
            receivers = listOf(receiver),
            activeReceiverId = "vu",
            recentChannels = listOf(LastChannel(reference = "x")),
            recentByReceiver = mapOf("vu" to listOf(LastChannel(reference = "x"))),
            liveSubtitles = LiveSubtitles(mode = LiveSubtitles.Mode.ON, language = "de"),
            lastChannels = mapOf("vu" to LastChannel(reference = "guest-pick")),
        )
        val after = HotelCleanupPlan.settings(before, HotelConfig(enabled = true, startSat = start, startReceiverId = "vu"))
        assertTrue(after.recentChannels.isEmpty())
        assertEquals(emptyList<LastChannel>(), after.recentByReceiver["vu"])
        assertEquals(LiveSubtitles(), after.liveSubtitles)
        assertEquals(start, after.lastChannels["vu"])
        assertEquals(LastLiveSource.ENIGMA2, after.lastLiveSource)
        // Receivers and everything else stay.
        assertEquals(before.receivers, after.receivers)
    }

    @Test fun iptvStartChannel() {
        val iptv = LastIptvChannel(source = "xtream:a", categoryId = "1", streamId = "42", name = "NOS")
        val after = HotelCleanupPlan.withStartChannel(AppSettings(), HotelConfig(enabled = true, source = HotelSource.IPTV, startIptv = iptv))
        assertEquals(iptv, after.lastIptvChannels["xtream:a"])
        assertEquals(LastLiveSource.IPTV, after.lastLiveSource)
    }

    @Test fun hiddenLastChannelDropped() {
        val settings = AppSettings(lastChannels = mapOf("vu" to LastChannel(reference = "r", bouquetReference = "xxx")))
        val config = HotelConfig(enabled = true, allowedBouquets = setOf("fav"))
        assertNull(HotelCleanupPlan.dropHiddenLastChannel(settings, config).lastChannels["vu"])
        assertEquals(settings, HotelCleanupPlan.dropHiddenLastChannel(settings, config.copy(allowedBouquets = null)))
    }

    @Test fun onlyHotelProfileRows() {
        data class Row(val id: String, val profile: String)
        val rows = listOf(Row("a", "hotel"), Row("b", "enrico"), Row("c", "kids"), Row("d", "hotel"))
        assertEquals(listOf("a", "d"), HotelCleanupPlan.rowsOfHotel(rows, "hotel") { it.profile }.map { it.id })
        // Without a hotel profile nothing at all is cleaned.
        assertTrue(HotelCleanupPlan.rowsOfHotel(rows, "") { it.profile }.isEmpty())
    }

    @Test fun keepsBoostAndFavourites() {
        assertTrue(HotelCleanupPlan.KEEPS_AUDIO_BOOST)
        assertTrue(HotelCleanupPlan.KEEPS_FAVOURITES)
    }
}
