package com.enigmacast.domain.person

import com.enigmacast.domain.torrent.TmdbTitle
import com.enigmacast.domain.torrent.TorrentKind
import com.enigmacast.domain.xtream.XtreamItem
import com.enigmacast.domain.xtream.XtreamSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TitleTrailTest {
    private val film = XtreamItem("7", "I vitelloni", XtreamSection.MOVIES, "c", providerId = "p1")
    private val iptv = TrailOrigin.Iptv("acc", film, "home")
    private val torrent = TrailOrigin.Torrent(TmdbTitle(603, TorrentKind.MOVIE, "The Matrix", 1999, null, null, ""), null)
    private fun step(origin: TrailOrigin = iptv, target: String = "tmdb:MOVIE:1") = TrailStep(origin, "Alberto Sordi|Alberto", "movie:12", target)

    @Test fun backPopsOnlyTheMatchingPage() {
        val trail = TitleTrail()
        trail.push(step())
        assertNull(trail.back("tmdb:MOVIE:2"))
        assertEquals(1, trail.depth)
        assertEquals(step(), trail.back("tmdb:MOVIE:1"))
        assertEquals(0, trail.depth)
        assertNull(trail.back("tmdb:MOVIE:1"))
    }

    @Test fun restoreOnlyOnTheSamePageAndOnce() {
        val trail = TitleTrail()
        trail.push(step())
        trail.back("tmdb:MOVIE:1")
        assertNull(trail.takeRestore(torrent))
        val other = TrailOrigin.Iptv("acc", film.copy(name = "x"), null)
        assertEquals(step(), trail.takeRestore(other))
        assertNull(trail.takeRestore(iptv))
    }

    @Test fun creditRestoredForTheSameActorOnce() {
        val trail = TitleTrail()
        trail.markCredit(step())
        assertNull(trail.takeCredit("Other|x"))
        assertEquals("movie:12", trail.takeCredit("Alberto Sordi|Alberto"))
        assertNull(trail.takeCredit("Alberto Sordi|Alberto"))
    }

    @Test fun nestedJumpsWalkBackInOrder() {
        val trail = TitleTrail()
        trail.push(step(iptv, "tmdb:MOVIE:1"))
        trail.push(step(torrent, TitleTrail.iptvKey(film)))
        assertEquals(torrent, trail.back(TitleTrail.iptvKey(film))?.origin)
        assertEquals(iptv, trail.back("tmdb:MOVIE:1")?.origin)
    }

    @Test fun depthIsBounded() {
        val trail = TitleTrail(maxDepth = 3)
        repeat(5) { trail.push(step(target = "t$it")) }
        assertEquals(3, trail.depth)
        assertEquals("t4", trail.back("t4")?.targetKey)
    }

    @Test fun keys() {
        assertEquals("tmdb:MOVIE:603", TitleTrail.tmdbKey(torrent.title))
        assertEquals("a|b", TitleTrail.actorKey("a", "b"))
    }
}
