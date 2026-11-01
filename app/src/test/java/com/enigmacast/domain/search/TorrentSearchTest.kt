package com.enigmacast.domain.search

import com.enigmacast.domain.torrent.TmdbTitle
import com.enigmacast.domain.torrent.TorrentKind
import org.junit.Assert.assertEquals
import org.junit.Test

class TorrentSearchTest {
    private fun t(id: Int, kind: TorrentKind) = TmdbTitle(id, kind, "T$id", 2020, null, null, "")

    @Test
    fun filmsAndSeriesAlternateAndAreCapped() {
        val films = (1..25).map { t(it, TorrentKind.MOVIE) }
        val series = (1..3).map { t(it, TorrentKind.TV) }
        val merged = TorrentSearch.merge(films, series)
        assertEquals(20, merged.size)
        assertEquals(listOf("MOVIE:1", "TV:1", "MOVIE:2", "TV:2"), merged.take(4).map { it.key })
    }

    @Test
    fun doublesAreDropped() {
        val f = t(1, TorrentKind.MOVIE)
        assertEquals(1, TorrentSearch.merge(listOf(f, f), emptyList()).size)
    }

    @Test
    fun cardCarriesKeyKindAndYear() {
        val card = TorrentSearch.card(t(7, TorrentKind.TV))
        assertEquals("TV:7", card.key)
        assertEquals("s", card.kind)
        assertEquals("2020", card.subtitle)
    }
}
