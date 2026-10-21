package com.enigmacast.domain.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyRepeatGateTest {
    @Test fun firstPressAlwaysPasses() {
        val gate = KeyRepeatGate()
        assertTrue(gate.accept(0, 0, 10_000, 5))
        assertTrue(gate.accept(0, 0, 10_000, 5))
    }

    @Test fun oneRepeatPerFrame() {
        val gate = KeyRepeatGate()
        assertTrue(gate.accept(0, 100, 100, 1))
        assertFalse("same frame", gate.accept(1, 150, 150, 1))
        assertTrue(gate.accept(2, 200, 200, 2))
        assertFalse(gate.accept(3, 210, 210, 2))
    }

    @Test fun staleRepeatsAreDropped() {
        val gate = KeyRepeatGate(maxLagMs = 120)
        assertTrue(gate.accept(0, 0, 0, 0))
        assertFalse(gate.accept(1, 50, 300, 9))
        assertTrue(gate.accept(2, 280, 300, 10))
    }

    /**
     * Holding Down for 2 s with repeats every 50 ms while each move costs 80 ms of main-thread
     * work (what a list that recomposes per focus step can cost on a TV box). Without the gate
     * every repeat runs, so moves are still being worked off long after the key is released.
     */
    @Test fun heldKeyNoLongerRunsOnAfterRelease() {
        fun simulate(gate: KeyRepeatGate?): Pair<Int, Long> {
            val events = (0 until 40).map { it * 50L } // created 0..1950 ms
            var clock = 0L
            var frame = 0L
            var moves = 0
            events.forEachIndexed { i, created ->
                clock = maxOf(clock, created)
                if (gate == null || gate.accept(i, created, clock, frame)) {
                    clock += 80 // the move
                    frame++ // a frame is drawn after it
                    moves++
                }
            }
            return moves to (clock - 1950) // how long after release the list kept moving
        }
        val (rawMoves, rawTail) = simulate(null)
        val (gatedMoves, gatedTail) = simulate(KeyRepeatGate())
        assertEquals(40, rawMoves)
        assertTrue("ungated runs on ${rawTail} ms", rawTail > 1000)
        assertTrue("gated runs on ${gatedTail} ms", gatedTail <= 200)
        assertTrue(gatedMoves in 20..30)
    }
}
