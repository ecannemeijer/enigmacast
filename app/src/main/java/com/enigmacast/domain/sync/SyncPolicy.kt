package com.enigmacast.domain.sync

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What a refresh setting covers. */
enum class SyncKind { CHANNELS, EPG, FILMS }

/**
 * "N× per day" means: refresh when the last successful refresh is older than 24 h / N.
 * WorkManager checks once an hour (and the app checks at start), so a refresh happens at
 * most about an hour after it falls due. 0 is Off: never automatic, only "Refresh now"
 * and the first fill of an empty cache. [FOLLOW_EPG] (films only) uses the EPG setting.
 */
object SyncPolicy {
    const val OFF = 0
    const val FOLLOW_EPG = -1
    val CHOICES = listOf(OFF, 1, 2, 3, 4, 5, 7)
    const val CHECK_INTERVAL_MIN = 60L

    fun intervalMs(timesPerDay: Int): Long? =
        if (timesPerDay <= 0) null else 24L * 60 * 60 * 1000 / timesPerDay

    /** Due when never done, or when the interval has passed. Off is only due when nothing is cached. */
    fun isDue(timesPerDay: Int, lastSuccessMs: Long, nowMs: Long, hasData: Boolean): Boolean {
        if (!hasData || lastSuccessMs <= 0L) return true
        val interval = intervalMs(timesPerDay) ?: return false
        return nowMs - lastSuccessMs >= interval
    }

    fun filmsTimesPerDay(filmsSetting: Int, epgSetting: Int): Int =
        if (filmsSetting == FOLLOW_EPG) epgSetting else filmsSetting
}

/** "Today" on the TV's clock: [start] is local midnight, [end] the next one. Seconds since the epoch. */
data class DayWindow(val date: LocalDate, val startSec: Long, val endSec: Long) {
    /** Films for today's home page: until midnight, or until 03:00 when it is already late. */
    fun homeEndSec(nowSec: Long): Long = if (endSec - nowSec < 3 * 3600) endSec + 3 * 3600 else endSec

    /** EPG older than the start of yesterday is deleted. */
    val purgeBeforeSec: Long get() = startSec - 24 * 3600

    companion object {
        fun of(nowMs: Long, zone: ZoneId): DayWindow {
            val date = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            return DayWindow(date, date.atStartOfDay(zone).toEpochSecond(), date.plusDays(1).atStartOfDay(zone).toEpochSecond())
        }
    }
}

/** Which cached films Home shows at a moment, and how the rows are built. Pure for tests. */
object HomeClock {
    /** Room query window: from 6 h before local midnight (late films from yesterday) to the end of today's home window. */
    fun queryWindow(nowMs: Long, zone: ZoneId): Pair<Long, Long> {
        val day = DayWindow.of(nowMs, zone)
        return (day.startSec - 6 * 3600) to day.homeEndSec(nowMs / 1000)
    }

    fun primeTimeStartSec(nowMs: Long, zone: ZoneId, hour: Int = com.enigmacast.domain.films.FilmRanking.PRIME_TIME_HOUR): Long {
        val day = DayWindow.of(nowMs, zone)
        return day.date.atTime(hour, 0).atZone(zone).toEpochSecond()
    }

    /** Hero and rows for [nowMs]: films that have not ended and start before the end of today's window. */
    fun compute(films: List<com.enigmacast.domain.films.HomeFilm>, nowMs: Long, zone: ZoneId): Pair<com.enigmacast.domain.films.HomeFilm?, List<com.enigmacast.domain.films.HomeRow>> {
        val nowSec = nowMs / 1000
        val end = DayWindow.of(nowMs, zone).homeEndSec(nowSec)
        val today = films.filter { it.event.endEpochSec > nowSec && it.event.beginEpochSec < end }
        return com.enigmacast.domain.films.FilmRanking.hero(today, nowSec) to
            com.enigmacast.domain.films.FilmRanking.rows(today, nowSec, primeTimeStartSec(nowMs, zone))
    }
}
