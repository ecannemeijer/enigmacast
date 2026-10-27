package com.enigmacast.live

import com.enigmacast.domain.tuner.TunerVerdict
import org.junit.Assert.assertEquals
import org.junit.Test

class ZapReleaseTest {
    @Test
    fun onlyTheRestOfTheReleaseWaitIsLeftAfterTheTunerCheck() {
        assertEquals(400L, ZapRelease.remainingWaitMs(TunerVerdict.Free(1, 2), 0, 400))
        assertEquals(150L, ZapRelease.remainingWaitMs(TunerVerdict.Free(1, 2), 250, 400))
        assertEquals(0L, ZapRelease.remainingWaitMs(TunerVerdict.Unknown, 900, 400))
    }

    @Test
    fun noWaitWhenAnotherTunerIsFreeAnyway() {
        assertEquals(0L, ZapRelease.remainingWaitMs(TunerVerdict.Free(2, 4), 10, 400))
    }

    @Test
    fun aFullOrUnknownCheckKeepsTheWait() {
        assertEquals(300L, ZapRelease.remainingWaitMs(TunerVerdict.Full(2, 2, emptyList()), 100, 400))
        assertEquals(300L, ZapRelease.remainingWaitMs(TunerVerdict.Unknown, 100, 400))
    }
}
