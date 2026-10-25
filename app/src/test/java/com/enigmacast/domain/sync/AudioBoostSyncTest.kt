package com.enigmacast.domain.sync

import com.enigmacast.data.db.SyncOutboxRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioBoostSyncTest {
    @Test fun recordKeyComesFromThePayload() {
        val payload = AudioBoostSync.payload("iptv:p1:42", 3, 1_000L)
        assertEquals("boost/live/iptv:p1:42", LibrarySync.keyOf(payload))
        assertEquals(AudioBoostSync.recordKey("iptv:p1:42"), LibrarySync.keyOf(payload))
        val body = LibrarySync.pushBody(listOf(SyncOutboxRow("c1", "p", AudioBoostSync.ENTITY, "upsert", payload, 1_000L, "d")))
        assertTrue(body.contains("\"entity\":\"audio_boost\""))
        assertTrue(body.contains("\"key\":\"boost/live/iptv:p1:42\""))
        assertTrue(body.contains("\"level\":3"))
    }

    @Test fun parseReadsLevelsAndClampsThem() {
        val key = "e2:1:0:19:1b1f:802:2:11a0000:0:0:0:"
        assertEquals(AudioBoostSync.Change(key, 5), AudioBoostSync.parse(AudioBoostSync.recordKey(key), AudioBoostSync.payload(key, 9, 1L)))
        assertEquals(AudioBoostSync.Change(key, 0), AudioBoostSync.parse(AudioBoostSync.recordKey(key), AudioBoostSync.payload(key, 0, 1L)))
        // No payload: the key from the record key, level off.
        assertEquals(AudioBoostSync.Change("iptv:p:7", 0), AudioBoostSync.parse("boost/live/iptv:p:7", null))
        assertNull(AudioBoostSync.parse("iptv/m/7", null))
        assertNull(AudioBoostSync.parse("", "not json"))
    }

    @Test fun newerChangeWins() {
        assertEquals(SyncApply.ACCEPT, SyncConflict.decide(null, null, 1_000L, "a"))
        assertEquals(SyncApply.ACCEPT, SyncConflict.decide(1_000L, "a", 2_000L, "b"))
        assertEquals(SyncApply.REJECT, SyncConflict.decide(2_000L, "b", 1_000L, "c"))
        assertEquals(SyncApply.REPLAY, SyncConflict.decide(2_000L, "b", 2_000L, "b"))
    }
}
