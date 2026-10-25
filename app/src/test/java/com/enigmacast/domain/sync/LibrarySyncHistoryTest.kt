package com.enigmacast.domain.sync

import com.enigmacast.data.db.EpisodeHistoryRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibrarySyncHistoryTest {
    @Test fun historyRoundTrip() {
        val row = EpisodeHistoryRow("acc", "e", "42", "7", 120_000, 2_400_000, true, 99, "profile")
        val payload = LibrarySync.historyPayload(row)
        val back = LibrarySync.historyFrom("profile", Triple("acc", "e", "42"), payload, 99)
        assertEquals(row, back)
        val notWatched = row.copy(watched = false, seriesId = null)
        assertEquals(notWatched, LibrarySync.historyFrom("profile", Triple("acc", "e", "42"), LibrarySync.historyPayload(notWatched), 99))
    }

    @Test fun unreadablePayload() {
        assertNull(LibrarySync.historyFrom("p", Triple("a", "e", "1"), "not json", 1))
        assertNull(LibrarySync.historyFrom("p", Triple("a", "e", "1"), null, 1))
    }
}
