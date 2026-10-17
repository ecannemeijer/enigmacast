package com.enigmacast.domain.xtream

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Server archive of a live channel: Xtream timeshift, or an M3U catchup-source template. */
object Catchup {
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm")

    /** Days the programme may be replayed. A template without `catchup-days` still keeps a week. */
    fun windowDays(item: XtreamItem): Int = when {
        item.archiveDays > 0 -> item.archiveDays
        item.catchupSource.isNotBlank() -> 7
        else -> 0
    }

    /** A programme that has already ended and still sits inside the archive window. */
    fun inWindow(nowSec: Long, startSec: Long, endSec: Long, archiveDays: Int): Boolean {
        if (archiveDays <= 0 || endSec > nowSec) return false
        return startSec >= nowSec - archiveDays * 86_400L
    }

    fun canPlay(item: XtreamItem, event: XtreamEpg, nowSec: Long): Boolean =
        inWindow(nowSec, event.startSec, event.endSec, windowDays(item))

    /**
     * Programmes the guide column shows: [pastDays] back (so catch-up can be chosen)
     * and [futureHours] ahead. At least the last day, even when the channel has no archive.
     */
    fun listed(events: List<XtreamEpg>, nowSec: Long, pastDays: Int, futureHours: Int = 36): List<XtreamEpg> {
        val from = nowSec - pastDays.coerceAtLeast(1) * 86_400L
        val until = nowSec + futureHours * 3600L
        return events.filter { it.endSec > from && it.startSec < until }.sortedBy { it.startSec }
    }

    /**
     * Timeshift address: `/timeshift/user/pass/minutes/yyyy-MM-dd:HH-mm/id.ext`.
     * The stamp uses [zone] because panels store the guide in the local clock.
     */
    fun xtream(
        account: XtreamAccount,
        streamId: String,
        startSec: Long,
        durationSec: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val minutes = ((durationSec + 59) / 60).toInt().coerceAtLeast(1)
        val stamp = Instant.ofEpochSecond(startSec).atZone(zone).format(STAMP)
        val ext = if (account.output == "m3u8") "m3u8" else "ts"
        return "${account.server}/timeshift/${path(account.username)}/${path(account.password)}/$minutes/$stamp/$streamId.$ext"
    }

    /**
     * Fills an M3U `catchup-source`. `{utc}` `{start}` `{timestamp}` are the start in unix seconds,
     * `{utcend}` `{end}` the end, `{duration}` the length, `{offset}` how long ago it started.
     * `%Y` `%m` `%d` `%H` `%M` `%S` are the start in [zone].
     */
    fun m3u(
        template: String,
        startSec: Long,
        endSec: Long,
        nowSec: Long = System.currentTimeMillis() / 1000,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val start = Instant.ofEpochSecond(startSec).atZone(zone)
        val duration = (endSec - startSec).coerceAtLeast(0)
        return template
            .replace("{utc}", startSec.toString())
            .replace("{start}", startSec.toString())
            .replace("{timestamp}", startSec.toString())
            .replace("{utcend}", endSec.toString())
            .replace("{end}", endSec.toString())
            .replace("{duration}", duration.toString())
            .replace("{offset}", (nowSec - startSec).coerceAtLeast(0).toString())
            .replace("%Y", "%04d".format(start.year))
            .replace("%m", "%02d".format(start.monthValue))
            .replace("%d", "%02d".format(start.dayOfMonth))
            .replace("%H", "%02d".format(start.hour))
            .replace("%M", "%02d".format(start.minute))
            .replace("%S", "%02d".format(start.second))
    }

    fun url(account: XtreamAccount?, item: XtreamItem, event: XtreamEpg, nowSec: Long = System.currentTimeMillis() / 1000): String? {
        if (!canPlay(item, event, nowSec)) return null
        if (item.catchupSource.isNotBlank()) return m3u(item.catchupSource, event.startSec, event.endSec, nowSec)
        val login = account ?: return null
        return xtream(login, item.id, event.startSec, event.endSec - event.startSec)
    }

    private fun path(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
