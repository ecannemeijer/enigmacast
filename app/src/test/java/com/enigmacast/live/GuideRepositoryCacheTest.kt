package com.enigmacast.live

import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.epg.TimerResult
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.source.EpgSource
import com.enigmacast.domain.sync.GuideCacheReader
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import com.enigmacast.domain.epg.FallbackEpgLookup
import org.junit.Assert.assertTrue
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

    private class Cache(
        val e: GuideEvent?,
        val channels: List<BouquetEntry> = emptyList(),
    ) : GuideCacheReader {
        override suspend fun bouquetEvents(receiver: ReceiverConfig, bouquetReference: String, fromSec: Long, toSec: Long) = e?.let { listOf(it) }
        override suspend fun serviceEvents(receiver: ReceiverConfig, serviceReference: String) = e?.let { listOf(it) }
        override suspend fun televisionBouquets(receiver: ReceiverConfig): List<BouquetEntry>? = null
        override suspend fun channelsInBouquet(receiver: ReceiverConfig, bouquetReference: String): List<BouquetEntry>? =
            channels.takeIf { it.isNotEmpty() }
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

    private class OfflineEpg : EpgSource {
        var calls = 0
        override suspend fun serviceEvents(config: ReceiverConfig, serviceReference: String): List<GuideEvent> {
            calls++
            error("offline")
        }
        override suspend fun bouquetEvents(config: ReceiverConfig, bouquetReference: String, startSec: Long, minutes: Int): List<GuideEvent> {
            calls++
            error("offline")
        }
        override suspend fun record(config: ReceiverConfig, serviceReference: String, eventId: Int) = TimerResult(true, "")
        override suspend fun search(config: ReceiverConfig, query: String) = emptyList<com.enigmacast.domain.search.EpgSearchEvent>()
    }

    private class NamedFallback(val event: GuideEvent) : FallbackEpgLookup {
        override suspend fun eventsFor(name: String, reference: String, tvgId: String, fromSec: Long, toSec: Long) =
            if (name.isNotBlank()) listOf(event.copy(reference = reference)) else emptyList()
        override suspend fun eventsByName(names: Collection<String>, tvgIds: Map<String, String>, fromSec: Long, toSec: Long) =
            names.filter { it.isNotBlank() }.associateWith { listOf(event) }
    }

    @Test fun localChunkShowsFallbackWhenTheReceiverFails() = runTest {
        val channel = BouquetEntry("Italia1 HD", ref)
        val fb = GuideEvent(9, ref, "The Tom and Jerry Show", "", "", 1000, 600)
        val epg = OfflineEpg()
        val repo = GuideRepository(epg, Cache(null, listOf(channel)), NamedFallback(fb))
        val local = repo.localBouquetChunk(receiver, "bq", 0)
        assertEquals("The Tom and Jerry Show", local.single().title)
        assertEquals(ref, local.single().reference)
        assertEquals(0, epg.calls)
        val chunk = repo.bouquetChunk(receiver, "bq", 0)
        assertEquals("The Tom and Jerry Show", chunk!!.single().title)
        assertTrue(epg.calls >= 1)
    }
}
