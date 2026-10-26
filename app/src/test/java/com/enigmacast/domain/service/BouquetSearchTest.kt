package com.enigmacast.domain.service

import com.enigmacast.domain.model.BouquetEntry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class BouquetSearchTest {
    private val tv = BouquetEntry("TV", "b:tv")
    private val news = BouquetEntry("News", "b:news")
    private val empty = BouquetEntry("Empty", "b:empty")
    private val ch1 = BouquetEntry("NPO 1", "1:0:19:4B1:C:...:0:0:0:")
    private val ch2 = BouquetEntry("CNN", "1:0:1:2:3:4:5:0:0:0:")
    private val lists = mapOf(tv.reference to listOf(ch1), news.reference to listOf(ch2), empty.reference to emptyList())

    @Test
    fun standbyStopsAtTheFirstBouquetWithChannels() = runTest {
        val read = mutableListOf<String>()
        val result = BouquetSearch.find(listOf(empty, tv, news), hinted = null, serviceReference = "", located = null) {
            read += it.reference
            lists.getValue(it.reference)
        }
        assertEquals("b:tv", result.bouquetReference)
        assertEquals(listOf("b:empty", "b:tv"), read)
    }

    @Test
    fun cacheHintIsReadFirst() = runTest {
        val read = mutableListOf<String>()
        val result = BouquetSearch.find(listOf(tv, news), hinted = null, serviceReference = ch2.reference, located = "b:news") {
            read += it.reference
            lists.getValue(it.reference)
        }
        assertEquals("b:news", result.bouquetReference)
        assertEquals(listOf("b:news"), read)
    }

    @Test
    fun hintedBouquetComesBeforeTheCacheAndEachIsReadOnce() = runTest {
        val read = mutableListOf<String>()
        val result = BouquetSearch.find(listOf(tv, news), hinted = tv, serviceReference = ch2.reference, located = "b:tv") {
            read += it.reference
            lists.getValue(it.reference)
        }
        assertEquals("b:news", result.bouquetReference)
        assertEquals(listOf("b:tv", "b:news"), read)
    }

    @Test
    fun unknownServiceFallsBackToTheFirstNonEmptyBouquet() = runTest {
        val result = BouquetSearch.find(listOf(empty, news, tv), null, "1:0:1:9:9:9:9:0:0:0:", null) { lists.getValue(it.reference) }
        assertEquals("b:news", result.bouquetReference)
    }

    @Test
    fun nothingAnywhereGivesAnEmptyList() = runTest {
        val result = BouquetSearch.find(listOf(empty), null, ch1.reference, null) { lists.getValue(it.reference) }
        assertEquals("", result.bouquetReference)
        assertEquals(0, result.channels.size)
    }
}
