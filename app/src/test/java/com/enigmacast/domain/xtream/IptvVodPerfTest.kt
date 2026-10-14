package com.enigmacast.domain.xtream

import com.enigmacast.data.xtream.IptvVod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cards of a big category are built once off the main thread; this keeps that step cheap. */
class IptvVodPerfTest {
    @Test fun fiveThousandCardsBuildQuickly() {
        val items = (1..5000).map { XtreamItem("$it", "NL: Film number $it (20${it % 25 + 10})", XtreamSection.MOVIES, "3", icon = "http://cover.invalid/$it.jpg") }
        IptvVod.film(items.first(), null) // warm up
        val started = System.nanoTime()
        val films = items.map { IptvVod.film(it, null) }
        val ms = (System.nanoTime() - started) / 1_000_000
        println("IptvVod.film x5000: $ms ms")
        assertEquals(5000, films.size)
        assertTrue("took $ms ms", ms < 3000)
    }

    @Test fun keysAreUniqueAndStable() {
        val a = XtreamItem("7", "A", XtreamSection.MOVIES, "3")
        val b = XtreamItem("8", "A", XtreamSection.MOVIES, "3")
        assertTrue(IptvVod.key(a) != IptvVod.key(b))
        assertEquals(IptvVod.key(a), IptvVod.key(a.copy(icon = "x")))
    }
}
