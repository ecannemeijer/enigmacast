package com.enigmacast.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncConflictTest {
    @Test fun newerProgressWinsIncludingRewind() {
        assertEquals(SyncApply.ACCEPT, SyncConflict.decide(1_000, "a", 2_000, "b"))
        assertEquals(SyncApply.REJECT, SyncConflict.decide(2_000, "b", 1_000, "a"))
    }

    @Test fun sameChangeIsAReplay() {
        assertEquals(SyncApply.REPLAY, SyncConflict.decide(1_000, "a", 1_000, "a"))
        assertEquals(SyncApply.REPLAY, SyncConflict.decide(1_000, "a", 9_000, "a"))
    }

    @Test fun missingStoredRowIsAccepted() {
        assertEquals(SyncApply.ACCEPT, SyncConflict.decide(null, null, 10, "c"))
    }
}
