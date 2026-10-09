package com.enigmacast.domain.search

import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.recording.Recording
import com.enigmacast.domain.recording.RecordingPlace
import com.enigmacast.domain.recording.RecordingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class SearchGroupingTest {
    private val now = 1_700_000_000L
    private val ziggo = BouquetEntry("Formula 1", "1:7:1:0:0:0:0:0:0:0:")
    private val film = BouquetEntry("Film", "1:7:1:1:0:0:0:0:0:0:")
    private val sport1 = BouquetEntry("Formula 1 HD", "1:0:19:1:0:0:0:0:0:0:")
    private val race = BouquetEntry("RTL 7", "1:0:19:2:0:0:0:0:0:0:")

    @Test
    fun formula1SplitsNowLaterRecordingsAndChannels() {
        val live = programme("Formula 1", sport1, now - 600, 2 * 3600, "Formula 1 HD")
        val laterRace = programme("Formula 1 — Race", race, now + 86_400, 2 * 3600, "RTL 7")
        val repeat = programme("Formula 1", sport1, now - 600, 2 * 3600, "Formula 1 HD")
        val described = programme("News", race, now - 100, 600, "RTL 7")
        val recording = Recording(
            id = "rec",
            title = "Formula 1 qualifying",
            channelName = "Formula 1 HD",
            reference = sport1.reference,
            eventId = 3,
            beginEpochSec = now - 86_400,
            endEpochSec = now - 80_000,
            place = RecordingPlace.TV,
            status = RecordingStatus.READY,
            receiverId = "box",
        )
        val results = SearchGrouping.group(
            query = "formula 1",
            nowSec = now,
            programmes = listOf(live, laterRace, repeat, described),
            recordings = listOf(recording),
            bouquets = listOf(ziggo, film),
            channels = listOf(ListedChannel(ziggo, sport1), ListedChannel(film, race)),
        )
        assertEquals(listOf("Formula 1"), results.now.map { it.title })
        assertEquals("Formula 1 HD", results.now.single().channelName)
        assertEquals(listOf("Formula 1 — Race"), results.later.map { it.title })
        assertEquals(listOf("Formula 1 qualifying"), results.recordings.map { it.title })
        assertEquals(listOf("Formula 1", "Formula 1 HD"), results.stations.map { it.title })
        assertEquals(SearchKind.BOUQUET, results.stations.first().kind)
        assertEquals(SearchKind.CHANNEL, results.stations.last().kind)
        assertTrue(results.stations.none { it.kind == SearchKind.TMDB })
    }

    @Test
    fun shortQueryAndChannelNameOnARecordingStillMatch() {
        assertTrue(SearchGrouping.group("f", now, emptyList(), emptyList(), emptyList(), emptyList()).isEmpty)
        val recording = Recording(
            id = "rec",
            title = "Qualifying",
            channelName = "Formula 1",
            reference = sport1.reference,
            eventId = 1,
            beginEpochSec = now,
            endEpochSec = now + 60,
            place = RecordingPlace.RECEIVER,
            status = RecordingStatus.RECORDING,
            receiverId = "box",
        )
        val results = SearchGrouping.group("formula", now, emptyList(), listOf(recording), emptyList(), emptyList())
        assertEquals("rec", results.recordings.single().recordingId)
    }

    @Test
    fun searchForFillerStillFindsFormula1() {
        val live = programme("Formula 1", sport1, now - 600, 2 * 3600, "Formula 1 HD")
        val results = SearchGrouping.group("search for Formula 1", now, listOf(live), emptyList(), emptyList(), emptyList())
        assertEquals(listOf("Formula 1"), results.now.map { it.title })
    }

    @Test
    fun yesterdaysRecordingsAreOnlyFromThePreviousDay() {
        val zone = ZoneOffset.UTC
        val yesterday = Instant.ofEpochSecond(now).atZone(zone).toLocalDate().minusDays(1)
        val yestSec = yesterday.atStartOfDay(zone).toEpochSecond() + 3_600
        val old = recording("rec-old", "News", yestSec)
        val today = recording("rec-today", "News today", now)
        val results = SearchGrouping.group(
            query = "yesterday's recordings",
            nowSec = now,
            programmes = emptyList(),
            recordings = listOf(old, today),
            bouquets = emptyList(),
            channels = emptyList(),
            zone = zone,
        )
        assertEquals(listOf("News"), results.recordings.map { it.title })
        assertTrue(results.now.isEmpty())
        assertTrue(results.stations.isEmpty())
        val dutch = SearchGrouping.group("opnames van gisteren", now, emptyList(), listOf(old, today), emptyList(), emptyList(), zone)
        assertEquals(listOf("News"), dutch.recordings.map { it.title })
        val spoken = SearchQuery.parse("zoek naar gisteren", now, zone)
        assertEquals("", spoken.text)
        assertEquals(yesterday, spoken.recordingDay)
    }

    @Test
    fun headlinePutsChannelThenProgrammeAndBouquetThenChannel() {
        val nowHit = SearchHit(SearchKind.NOW, title = "Formula 1", channelName = "BBC One")
        assertEquals("BBC One  ·  Formula 1", SearchRows.headline(nowHit))
        val same = SearchHit(SearchKind.NOW, title = "BBC One", channelName = "BBC One")
        assertEquals("BBC One", SearchRows.headline(same))
        val channel = SearchHit(SearchKind.CHANNEL, title = "Rai 1", channelName = "Rai 1", bouquetName = "Favourites")
        assertEquals("Favourites  ·  Rai 1", SearchRows.headline(channel))
        assertEquals("", SearchRows.detail(nowHit, "Mon", "Bouquet"))
    }

    @Test
    fun playHitPrefersAnExactChannelName() {
        val channel = SearchHit(SearchKind.CHANNEL, title = "BBC One", channelName = "BBC One")
        val live = SearchHit(SearchKind.NOW, title = "News", channelName = "BBC One")
        val results = SearchResults(now = listOf(live), stations = listOf(channel))
        assertEquals(channel, results.playHit("BBC One"))
        assertEquals(live, results.playHit("News"))
    }

    @Test
    fun placeOfFindsTheBouquetThatContainsTheChannel() {
        val channels = listOf(ListedChannel(film, race), ListedChannel(ziggo, sport1))
        val place = SearchGrouping.placeOf("1:0:19:1:0:0:0:0:0:0:", channels)
        assertEquals("Formula 1", place?.bouquet?.name)
        assertEquals(null, SearchGrouping.placeOf("1:0:19:9:0:0:0:0:0:0:", channels))
    }

    private fun programme(title: String, channel: BouquetEntry, begin: Long, duration: Long, name: String) =
        EpgSearchEvent(GuideEvent(1, channel.reference, title, "", "", begin, duration), name)

    private fun recording(id: String, title: String, begin: Long) = Recording(
        id = id,
        title = title,
        channelName = "BBC One",
        reference = sport1.reference,
        eventId = 1,
        beginEpochSec = begin,
        endEpochSec = begin + 60,
        place = RecordingPlace.TV,
        status = RecordingStatus.READY,
        receiverId = "box",
    )
}
