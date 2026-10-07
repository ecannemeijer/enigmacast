package com.enigmacast.domain.tuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestartBudgetTest {
    @Test
    fun allowsThreeRestartsWithGrowingPausesThenStops() {
        val budget = RestartBudget()
        assertEquals(1_000L, budget.next())
        assertEquals(2_000L, budget.next())
        assertEquals(4_000L, budget.next())
        assertTrue(budget.exhausted)
        assertNull(budget.next())
        assertNull(budget.next())
    }

    @Test
    fun aPlayingStreamOrAUserZapStartsAFreshBudget() {
        val budget = RestartBudget()
        repeat(3) { budget.next() }
        budget.reset()
        assertFalse(budget.exhausted)
        assertEquals(1_000L, budget.next())
    }

    @Test
    fun pausesNeverExceedTheMaximum() {
        val budget = RestartBudget(maxAttempts = 10, firstDelayMs = 1_000, maxDelayMs = 8_000)
        val delays = generateSequence { budget.next() }.toList()
        assertEquals(10, delays.size)
        assertEquals(8_000L, delays.max())
    }
}
