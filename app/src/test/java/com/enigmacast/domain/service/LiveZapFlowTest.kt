package com.enigmacast.domain.service

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LiveZapFlowTest {
    private val npo = "1:0:19:1:2:3:4:0:0:0:"
    private val rtl = "1:0:19:9:2:3:4:0:0:0:"

    @Test
    fun successfulZapStartsStreamOnlyAfterTargetIsCurrent() = runTest {
        val events = mutableListOf<String>()
        var current = "1:0:1:0:0:0:0:0:0:0:"
        val logs = mutableListOf<String>()
        val outcome = LiveZapFlow.run(
            token = 1,
            isCurrent = { it == 1 },
            reference = npo,
            streamUrl = "http://192.168.1.10:8001/npo",
            releaseStream = { events += "release" },
            zap = {
                events += "zap"
                current = "$npo" + "NPO1"
            },
            currentReference = {
                events += "poll"
                current
            },
            startStream = { events += "start" },
            cleanupDelayMs = 100,
            pollMs = 50,
            log = { logs += it },
        )
        assertEquals(LiveZapFlow.Outcome.Started, outcome)
        assertEquals(listOf("release", "zap", "poll", "start"), events)
        assertTrue(logs.contains("tune requested: $npo"))
        assertTrue(logs.contains("zap accepted"))
        assertTrue(logs.contains("target service confirmed"))
        assertTrue(logs.contains("starting stream: http://192.168.1.10:8001/npo"))
        assertTrue(logs.indexOf("zap accepted") < logs.indexOf("starting stream: http://192.168.1.10:8001/npo"))
        assertTrue(logs.indexOf("old stream released") < logs.indexOf("sending zap"))
    }

    @Test
    fun staleTokenAfterReleaseDoesNotZapOrStream() = runTest {
        var token = 1
        val events = mutableListOf<String>()
        val outcome = LiveZapFlow.run(
            token = 1,
            isCurrent = { it == token },
            reference = npo,
            streamUrl = "http://box:8001/npo",
            releaseStream = {
                events += "release"
                token = 2
            },
            zap = { events += "zap" },
            currentReference = { events += "poll"; npo },
            startStream = { events += "start" },
            cleanupDelayMs = 0,
            log = {},
        )
        assertEquals(LiveZapFlow.Outcome.Stale, outcome)
        assertEquals(listOf("release"), events)
    }

    @Test
    fun staleTokenAfterZapDoesNotStartTheFirstStream() = runTest {
        var token = 1
        val started = mutableListOf<String>()
        val first = async {
            LiveZapFlow.run(
                token = 1,
                isCurrent = { it == token },
                reference = npo,
                streamUrl = "http://box:8001/npo",
                releaseStream = {},
                zap = {
                    token = 2
                    delay(500)
                },
                currentReference = { npo },
                startStream = { started += npo },
                cleanupDelayMs = 0,
                log = {},
            )
        }
        delay(50)
        val second = async {
            LiveZapFlow.run(
                token = 2,
                isCurrent = { it == token },
                reference = rtl,
                streamUrl = "http://box:8001/rtl",
                releaseStream = {},
                zap = { delay(10) },
                currentReference = { rtl },
                startStream = { started += rtl },
                cleanupDelayMs = 0,
                log = {},
            )
        }
        assertEquals(LiveZapFlow.Outcome.Stale, first.await())
        assertEquals(LiveZapFlow.Outcome.Started, second.await())
        assertEquals(listOf(rtl), started)
    }

    @Test
    fun timeoutDoesNotStartStream() = runTest {
        val logs = mutableListOf<String>()
        var started = false
        val outcome = LiveZapFlow.run(
            token = 1,
            isCurrent = { true },
            reference = npo,
            streamUrl = "http://box:8001/npo",
            releaseStream = {},
            zap = {},
            currentReference = { rtl },
            startStream = { started = true },
            timeoutMs = 200,
            pollMs = 50,
            cleanupDelayMs = 0,
            log = { logs += it },
        )
        assertEquals(LiveZapFlow.Outcome.TimedOut, outcome)
        assertFalse(started)
        assertTrue(logs.contains("tune timeout"))
        assertTrue(logs.any { it == "current service: $rtl" })
    }

    @Test
    fun zapFailureDoesNotStartStream() = runTest {
        var started = false
        val logs = mutableListOf<String>()
        val outcome = LiveZapFlow.run(
            token = 1,
            isCurrent = { true },
            reference = npo,
            streamUrl = "http://box:8001/npo",
            releaseStream = {},
            zap = { throw IOException("Zap mislukt (500)") },
            currentReference = { npo },
            startStream = { started = true },
            cleanupDelayMs = 0,
            log = { logs += it },
        )
        assertTrue(outcome is LiveZapFlow.Outcome.ZapFailed)
        assertEquals("Zap mislukt (500)", (outcome as LiveZapFlow.Outcome.ZapFailed).error.message)
        assertFalse(started)
        assertTrue(logs.contains("zap failed"))
    }

    @Test
    fun pollErrorsAreIgnoredUntilTheTargetAppears() = runTest {
        var reads = 0
        var started = false
        val outcome = LiveZapFlow.run(
            token = 4,
            isCurrent = { it == 4 },
            reference = npo,
            streamUrl = "http://box:8001/npo",
            releaseStream = {},
            zap = {},
            currentReference = {
                reads += 1
                if (reads < 3) throw IOException("temporary")
                npo
            },
            startStream = { started = true },
            cleanupDelayMs = 0,
            log = {},
        )
        assertEquals(LiveZapFlow.Outcome.Started, outcome)
        assertTrue(started)
        assertEquals(3, reads)
    }

    @Test
    fun staleTokenInsideStartReleasesTheStreamAgain() = runTest {
        var token = 1
        var releases = 0
        val outcome = LiveZapFlow.run(
            token = 1,
            isCurrent = { it == token },
            reference = npo,
            streamUrl = "http://box:8001/npo",
            releaseStream = { releases += 1 },
            zap = {},
            currentReference = { npo },
            startStream = { token = 2 },
            cleanupDelayMs = 0,
            log = {},
        )
        assertEquals(LiveZapFlow.Outcome.Stale, outcome)
        assertEquals(2, releases)
    }

    @Test
    fun staleTokenDuringPollDoesNotStartStream() = runTest {
        var token = 1
        var started = false
        val outcome = async {
            LiveZapFlow.run(
                token = 1,
                isCurrent = { it == token },
                reference = npo,
                streamUrl = "http://box:8001/npo",
                releaseStream = {},
                zap = {},
                currentReference = {
                    token = 2
                    rtl
                },
                startStream = { started = true },
                timeoutMs = 2_500,
                pollMs = 50,
                cleanupDelayMs = 0,
                log = {},
            )
        }
        assertEquals(LiveZapFlow.Outcome.Stale, outcome.await())
        assertFalse(started)
    }
}
