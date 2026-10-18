package com.enigmacast.domain.sync

import com.enigmacast.data.db.IptvLibraryRow
import com.enigmacast.data.db.IptvResumeRow
import com.enigmacast.data.db.SyncOutboxRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySyncTest {
    private val film = IptvLibraryRow("abc1", "m", "42", "Film \"One\"\tHD", "https://img/p.jpg", "https://img/b.jpg", 2021, "7", "mkv", "http://x/42.mkv", 1_000L, "p1")

    @Test
    fun favoritePayloadCarriesTheWholeRowAndRoundTrips() {
        val payload = LibrarySync.favoritePayload(film)
        val key = LibrarySync.keyOfChange("abc1/m/42", payload)!!
        assertEquals(Triple("abc1", "m", "42"), key)
        assertEquals(film, LibrarySync.favoriteFrom("p1", key, payload, 5_000L, null))
    }

    @Test
    fun olderAppPayloadKeepsWhatThisDeviceHas() {
        // Older versions sent only source/kind/id/title/addedMs.
        val old = """{"source":"abc1","kind":"m","id":"42","title":"Film","addedMs":2000}"""
        val row = LibrarySync.favoriteFrom("p1", Triple("abc1", "m", "42"), old, 3_000L, film)
        assertEquals("Film", row.title)
        assertEquals(film.poster, row.poster)
        assertEquals(film.url, row.url)
        assertEquals(2021, row.year)
        assertEquals(2_000L, row.addedMs)
    }

    @Test
    fun deletesTakeTheirKeyFromTheRecordKey() {
        assertEquals(Triple("abc1", "e", "77"), LibrarySync.keyOfChange("abc1/e/77", "{}"))
        assertEquals(Triple("torrent", "e", "1399:1:2"), LibrarySync.keyOfChange("torrent/e/1399:1:2", ""))
        assertNull(LibrarySync.keyOfChange("broken", "{}"))
    }

    @Test
    fun progressRoundTrips() {
        val row = IptvResumeRow("abc1", "e", "77", 90_000, 2_400_000, 9_000, "Pilot", "p", "mp4", "", "3", "12", "Show", 1, 1, "p1")
        val payload = LibrarySync.progressPayload(row)
        assertEquals(row, LibrarySync.progressFrom("p1", LibrarySync.keyOfChange("", payload)!!, payload, 9_000, null))
    }

    @Test
    fun pushSendsNewestChangePerRecordAndSettlesAll() {
        fun progress(id: String, pos: Long, at: Long, change: String) = SyncOutboxRow(
            change, "p1", LibrarySync.PROGRESS, "upsert",
            LibrarySync.progressPayload(IptvResumeRow("abc1", "m", id, pos, 1, at, "t", null, "", "", "", null, null, 0, 0)), at, "",
        )
        val rows = listOf(progress("1", 10, 1, "a"), progress("1", 20, 2, "b"), progress("2", 5, 3, "c"),
            SyncOutboxRow("bad", "p1", LibrarySync.FAVORITE, "upsert", "not json", 4, ""))
        val batch = LibrarySync.batch(rows)
        assertEquals(listOf("b", "c"), batch.send.map { it.changeId })
        assertEquals(setOf("a", "b", "c", "bad"), batch.settled.toSet())
    }

    @Test
    fun pushBodyIsValidJsonWithKeys() {
        val row = SyncOutboxRow("c1", "p1", LibrarySync.FAVORITE, "upsert", LibrarySync.favoritePayload(film), 5, "")
        val root = Json.parseToJsonElement(LibrarySync.pushBody(listOf(row))).jsonObject
        val change = root["changes"]!!.jsonArray[0].jsonObject
        assertEquals("abc1/m/42", change["key"]!!.jsonPrimitive.content)
        assertEquals("Film \"One\"\tHD", change["payload"]!!.jsonObject["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun backfillSendsTheProfilesRowsWithTheirOwnTimes() {
        val resume = IptvResumeRow("abc1", "m", "42", 90_000, 1, 7_000, "Film", null, "", "", "", null, null, 0, 0, "p1")
        val untitled = resume.copy(id = "43", title = "")
        val other = film.copy(profileId = "p2")
        val rows = LibrarySync.backfill("p1", listOf(film, other), listOf(resume, untitled)) { "id:$it" }
        assertEquals(listOf("id:abc1/m/42", "id:abc1/m/42"), rows.map { it.changeId })
        assertEquals(listOf(1_000L, 7_000L), rows.map { it.updatedMs })
        assertTrue(rows.all { it.profileId == "p1" && it.op == "upsert" })
    }
}
