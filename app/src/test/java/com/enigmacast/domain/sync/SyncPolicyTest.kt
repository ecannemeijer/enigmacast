package com.enigmacast.domain.sync

import com.enigmacast.domain.films.FilmTestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class SyncPolicyTest {
    private val hour = 3_600_000L

    @Test
    fun timesPerDayMapToIntervals() {
        assertNull(SyncPolicy.intervalMs(SyncPolicy.OFF))
        assertEquals(24 * hour, SyncPolicy.intervalMs(1))
        assertEquals(12 * hour, SyncPolicy.intervalMs(2))
        assertEquals(8 * hour, SyncPolicy.intervalMs(3))
        assertEquals(6 * hour, SyncPolicy.intervalMs(4))
        assertEquals(24 * hour / 5, SyncPolicy.intervalMs(5))
        assertEquals(24 * hour / 7, SyncPolicy.intervalMs(7))
    }

    @Test
    fun dueWhenOldEnoughOrEmpty() {
        val now = 100 * hour
        assertTrue(SyncPolicy.isDue(2, now - 13 * hour, now, hasData = true))
        assertFalse(SyncPolicy.isDue(2, now - 11 * hour, now, hasData = true))
        assertTrue(SyncPolicy.isDue(2, now - 1 * hour, now, hasData = false))
        assertTrue(SyncPolicy.isDue(1, 0L, now, hasData = true))
    }

    @Test
    fun offOnlyFillsAnEmptyCache() {
        val now = 100 * hour
        assertFalse(SyncPolicy.isDue(SyncPolicy.OFF, now - 90 * hour, now, hasData = true))
        assertTrue(SyncPolicy.isDue(SyncPolicy.OFF, 0L, now, hasData = false))
    }

    @Test
    fun filmsFollowEpgByDefault() {
        assertEquals(3, SyncPolicy.filmsTimesPerDay(SyncPolicy.FOLLOW_EPG, 3))
        assertEquals(1, SyncPolicy.filmsTimesPerDay(1, 3))
    }
}

class DayWindowTest {
    private val zone = ZoneId.of("Europe/Amsterdam")

    private fun ms(y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun todayIsLocalMidnightToMidnight() {
        val day = DayWindow.of(ms(2026, 10, 12, 8, 13), zone)
        assertEquals(LocalDate.of(2026, 10, 12), day.date)
        assertEquals(ms(2026, 10, 12, 0) / 1000, day.startSec)
        assertEquals(ms(2026, 10, 13, 0) / 1000, day.endSec)
        assertEquals(ms(2026, 10, 11, 0) / 1000, day.purgeBeforeSec)
    }

    @Test
    fun rolloverAtMidnightMovesTheDay() {
        assertEquals(LocalDate.of(2026, 10, 12), DayWindow.of(ms(2026, 10, 12, 23, 59), zone).date)
        assertEquals(LocalDate.of(2026, 10, 13), DayWindow.of(ms(2026, 10, 13, 0, 1), zone).date)
    }

    @Test
    fun dstDayIs25Hours() {
        // 25 October 2026: clocks go back in Amsterdam.
        val day = DayWindow.of(ms(2026, 10, 25, 12), zone)
        assertEquals(25 * 3600L, day.endSec - day.startSec)
    }

    @Test
    fun lateEveningExtendsTo3am() {
        val now = ms(2026, 10, 12, 22, 30)
        val day = DayWindow.of(now, zone)
        assertEquals(ms(2026, 10, 13, 3) / 1000, day.homeEndSec(now / 1000))
        val morning = ms(2026, 10, 12, 9)
        assertEquals(day.endSec, day.homeEndSec(morning / 1000))
    }

    @Test
    fun homeRowsFromCachedFilmsFollowTheClock() {
        val now = ms(2026, 10, 12, 19, 0)
        val nowSec = now / 1000
        val films = listOf(
            FilmTestData.film(1, 7.5, 900, begin = nowSec - 1800, minutes = 120),
            FilmTestData.film(2, 8.0, 900, begin = ms(2026, 10, 12, 20, 30) / 1000),
            FilmTestData.film(3, 7.0, 900, begin = ms(2026, 10, 12, 15) / 1000, minutes = 100), // ended
            FilmTestData.film(4, 9.0, 900, begin = ms(2026, 10, 13, 20, 15) / 1000), // tomorrow
        )
        val (hero, rows) = HomeClock.compute(films, now, zone)
        assertEquals(2, hero?.movie?.id)
        val ids = rows.flatMap { r -> r.films.map { it.movie.id } }.toSet()
        assertEquals(setOf(1, 2), ids)

        // Next day at 00:30 the film that started at 20:30 yesterday has ended, tomorrow's is "today".
        val next = ms(2026, 10, 13, 0, 30)
        val (_, nextRows) = HomeClock.compute(films, next, zone)
        assertEquals(setOf(4), nextRows.flatMap { r -> r.films.map { it.movie.id } }.toSet())
    }

    @Test
    fun queryWindowCoversYesterdaysLateFilms() {
        val now = ms(2026, 10, 13, 0, 30)
        val (from, _) = HomeClock.queryWindow(now, zone)
        assertEquals(ms(2026, 10, 12, 18) / 1000, from)
    }
}
