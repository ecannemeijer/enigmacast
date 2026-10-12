package com.enigmacast.live

import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.epg.TimerResult
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.source.EpgSource
import com.enigmacast.domain.sync.GuideCacheReader
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GuideRepositoryCacheTest {
    private val receiver = ReceiverConfig(id = "box", name = "SF8008", host = "192.168.1.209")
    private val ref = "1:0:19:1B6C:C88:3:EB0000:0:0:0:"
    private val net = GuideEvent(1, ref, "Network", "", "", 1000, 600)
    private val db = GuideEvent(2, ref, "Room", "", "", 1000, 600)

    private class Epg(val e: GuideEvent) : EpgSource {
        var calls = 0
        override suspend fun serviceEvents(config: ReceiverConfig, serviceReference: String): List<GuideEvent> { calls++; return listOf(e) }
        override suspend fun bouquetEvents(config: ReceiverConfig, bouquetReference: String, startSec: Long, minutes: Int): List<GuideEvent> { calls++; return listOf(e) }
        override suspend fun record(config: ReceiverConfig, serviceReference: String, eventId: Int) = TimerResult(true, "")
        override suspend fun search(config: ReceiverConfig, query: String) = emptyList<com.enigmacast.domain.search.EpgSearchEvent>()
    }

    private class Cache(val e: GuideEvent?) : GuideCacheReader {
        override suspend fun bouquetEvents(receiver: ReceiverConfig, bouquetReference: String, fromSec: Long, toSec: Long) = e?.let { listOf(it) }
        override suspend fun serviceEvents(receiver: ReceiverConfig, serviceReference: String) = e?.let { listOf(it) }
        override suspend fun televisionBouquets(receiver: ReceiverConfig): List<BouquetEntry>? = null
        override suspend fun channelsInBouquet(receiver: ReceiverConfig, bouquetReference: String): List<BouquetEntry>? = null
    }

    @Test fun cacheHitSkipsTheReceiver() = runTest {
        val epg = Epg(net)
        val repo = GuideRepository(epg, Cache(db))
        assertEquals(listOf(db), repo.bouquetChunk(receiver, "bq", 0))
        assertEquals(listOf(db), repo.serviceEvents(receiver, ref))
        assertEquals(0, epg.calls)
    }

    @Test fun cacheMissAsksTheReceiver() = runTest {
        val epg = Epg(net)
        val repo = GuideRepository(epg, Cache(null))
        assertEquals(listOf(net), repo.bouquetChunk(receiver, "bq", 0))
        assertEquals(listOf(net), repo.serviceEvents(receiver, ref))
        assertEquals(2, epg.calls)
    }
}
