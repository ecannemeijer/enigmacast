package com.enigmacast.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class SingleFlightTest {
    @Test
    fun concurrentCallersShareOneLoad() = runTest {
        val flight = SingleFlight<Int>(ttlMs = 30_000) { 0L }
        val gate = CompletableDeferred<Unit>()
        var loads = 0
        val first = async { flight.get("a") { loads++; gate.await(); 7 } }
        val second = async { flight.get("a") { loads++; 8 } }
        yield(); yield()
        gate.complete(Unit)
        assertEquals(7, first.await())
        assertEquals(7, second.await())
        assertEquals(1, loads)
    }

    @Test
    fun resultIsReusedWithinTheTtlOnly() = runTest {
        var clock = 0L
        val flight = SingleFlight<Int>(ttlMs = 1_000) { clock }
        var loads = 0
        assertEquals(1, flight.get("a") { ++loads })
        clock = 999
        assertEquals(1, flight.get("a") { ++loads })
        clock = 1_000
        assertEquals(2, flight.get("a") { ++loads })
        assertEquals(3, flight.get("b") { ++loads })
    }

    @Test
    fun failuresAreNotKept() = runTest {
        val flight = SingleFlight<Int>(ttlMs = 30_000) { 0L }
        try {
            flight.get("a") { throw IOException("down") }
            fail()
        } catch (expected: IOException) {
        }
        assertEquals(5, flight.get("a") { 5 })
    }

    @Test
    fun forgetDropsKeptResults() = runTest {
        val flight = SingleFlight<Int>(ttlMs = 30_000) { 0L }
        var loads = 0
        flight.get("a") { ++loads }
        flight.forget()
        assertEquals(2, flight.get("a") { ++loads })
    }

    @Test
    fun waiterTakesOverWhenTheLoaderIsCancelled() = runTest {
        val flight = SingleFlight<Int>(ttlMs = 30_000) { 0L }
        val never = CompletableDeferred<Int>()
        val loader = async { flight.get("a") { never.await() } }
        yield()
        val waiter = async { flight.get("a") { 42 } }
        yield()
        loader.cancel()
        assertEquals(42, waiter.await())
    }
}
