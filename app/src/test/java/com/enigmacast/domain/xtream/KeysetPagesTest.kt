package com.enigmacast.domain.xtream

import com.enigmacast.ui.xtream.IptvCard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeysetPagesTest {
    private data class Row(val sort: Int, val id: String)

    /** In-memory table read like the DAO: ordered by (sort, id), rows after a position. */
    private fun table(n: Int, dupSortEvery: Int = 0) =
        (0 until n).map { Row(if (dupSortEvery > 0) it / dupSortEvery else it, "id%05d".format(it)) }

    private fun runRead(rows: List<Row>, first: Int, page: Int): Pair<List<Pair<Int, Boolean>>, List<Row>> = runBlocking {
        val calls = mutableListOf<Pair<Int, Boolean>>()
        val got = mutableListOf<Row>()
        KeysetPages.read(
            first = first, page = page,
            fetch = { after, limit ->
                rows.filter { after == null || it.sort > after.sort || (it.sort == after.sort && it.id > after.id) }.take(limit)
            },
            position = { KeysetPages.After(it.sort, it.id) },
            onChunk = { chunk, last -> calls += chunk.size to last; got += chunk },
        )
        calls to got
    }

    @Test fun firstChunkThenRestInOnePiece() {
        val rows = table(2345)
        val (calls, got) = runRead(rows, 200, 1000)
        assertEquals(listOf(200 to false, 2145 to true), calls)
        assertEquals(rows, got)
    }

    @Test fun smallCategoryIsOneCall() {
        val (calls, got) = runRead(table(57), 200, 1000)
        assertEquals(listOf(57 to true), calls)
        assertEquals(57, got.size)
    }

    @Test fun exactlyFirstChunkEndsWithEmptyLast() {
        val (calls, _) = runRead(table(200), 200, 1000)
        assertEquals(listOf(200 to false, 0 to true), calls)
    }

    @Test fun equalSortValuesAreNeitherSkippedNorRepeated() {
        val rows = table(1500, dupSortEvery = 7)
        val (_, got) = runRead(rows, 200, 64)
        assertEquals(rows, got)
    }

    @Test fun firstChunkCoversRememberedPoster() {
        assertEquals(200, KeysetPages.firstChunk(0))
        assertEquals(200 + 1500, KeysetPages.firstChunk(1500))
        assertTrue(KeysetPages.firstChunk(10) > 10)
    }

    @Test fun cardsBuiltInPartsHaveTheSameKeysAsAtOnce() {
        val items = listOf("1", "2", "1", "", "3", "2", "", "4").map { XtreamItem(it, "T$it", XtreamSection.MOVIES, "c") }
        val whole = IptvCard.of(items).map { it.key }
        val keyer = IptvCard.keyer()
        val parts = IptvCard.of(items.take(3), keyer).map { it.key } + IptvCard.of(items.drop(3), keyer, 3).map { it.key }
        assertEquals(whole, parts)
        assertEquals(whole.size, whole.toSet().size)
    }
}
