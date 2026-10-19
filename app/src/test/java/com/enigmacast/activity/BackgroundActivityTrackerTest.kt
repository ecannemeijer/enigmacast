package com.enigmacast.activity

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackgroundActivityTrackerTest {
    private var now = 10_000L
    private val tracker = BackgroundActivityTracker { now }
    private fun view() = ActivityRules.view(tracker.board.value, now)

    @Test fun shortTasksNeverShow() {
        val id = tracker.start(ActivityKind.CHANNELS)
        now += 500
        assertNull(view())
        tracker.finish(id)
        assertNull(view())
    }

    @Test fun runningThenDoneThenHidden() {
        val id = tracker.start(ActivityKind.GUIDE)
        now += 1_500
        assertEquals(ActivityView.Running(ActivityKind.GUIDE, null, null, 0), view())
        tracker.progress(id, 3, 12)
        val running = view() as ActivityView.Running
        assertEquals(3, running.done)
        assertEquals(12, running.total)
        assertEquals(0.25f, running.fraction!!, 0.001f)
        tracker.finish(id)
        assertEquals(ActivityView.Done, view())
        now += ActivityRules.DONE_MS
        assertNull(view())
    }

    @Test fun unknownTotalIsIndeterminate() {
        val id = tracker.start(ActivityKind.IPTV)
        tracker.progress(id, 1234, null)
        now += 2_000
        val running = view() as ActivityView.Running
        assertNull(running.fraction)
        tracker.progress(id, 0, 0)
        assertNull((view() as ActivityView.Running).total)
    }

    @Test fun oldestTaskShowsWithWaitingCount() {
        tracker.start(ActivityKind.CHANNELS)
        now += 100
        val iptv = tracker.start(ActivityKind.IPTV)
        now += 100
        tracker.start(ActivityKind.SYNC_SERVER)
        now += 1_500
        assertEquals(ActivityView.Running(ActivityKind.CHANNELS, null, null, 2), view())
        tracker.finish(iptv)
        assertEquals(1, (view() as ActivityView.Running).waiting)
    }

    @Test fun syncServerOnlyWhenSlow() {
        val id = tracker.start(ActivityKind.SYNC_SERVER)
        now += 3_000
        assertNull(view())
        now += 1_500
        assertEquals(ActivityKind.SYNC_SERVER, (view() as ActivityView.Running).kind)
        tracker.finish(id)
    }

    @Test fun failureShowsBrieflyAndNeverSticks() = runBlocking {
        try {
            tracker.track(ActivityKind.GUIDE) {
                now += 2_000
                error("receiver sent no guide")
            }
            fail()
        } catch (expected: IllegalStateException) {
        }
        assertTrue(tracker.board.value.tasks.isEmpty())
        assertEquals(ActivityView.Failed(ActivityKind.GUIDE), view())
        now += ActivityRules.FAILED_MS
        assertNull(view())
    }

    @Test fun cancellationFinishesQuietly() = runBlocking {
        try {
            tracker.track(ActivityKind.HOME) {
                now += 2_000
                throw CancellationException("stopped")
            }
        } catch (expected: CancellationException) {
        }
        assertTrue(tracker.board.value.tasks.isEmpty())
        assertEquals(ActivityView.Done, view())
    }

    @Test fun trackReturnsTheResultAndReportsProgress() = runBlocking {
        val result = tracker.track(ActivityKind.CHANNELS) { reporter ->
            reporter.progress(1, 4)
            assertEquals(4, tracker.board.value.tasks.single().total)
            "ok"
        }
        assertEquals("ok", result)
        assertTrue(tracker.board.value.tasks.isEmpty())
    }

    @Test fun doneOnlyAfterSomethingWasShownAndFreshAfterwards() {
        val first = tracker.start(ActivityKind.GUIDE)
        now += 2_000
        tracker.finish(first)
        now += ActivityRules.DONE_MS + 1
        // A new quick task after the Done window does not bring back Done.
        val quick = tracker.start(ActivityKind.CHANNELS)
        now += 200
        tracker.finish(quick)
        assertNull(view())
    }

    @Test fun nextChangeWakesTheWindowAtTheRightTime() {
        val board = ActivityRules.start(ActivityBoard(), ActivityTask(1, ActivityKind.GUIDE, startedMs = 0))
        assertEquals(ActivityKind.GUIDE.visibleAfterMs, ActivityRules.nextChangeMs(board, 0))
        val ended = ActivityRules.finish(board, 1, nowMs = 5_000, failed = false)
        assertEquals(5_000 + ActivityRules.DONE_MS, ActivityRules.nextChangeMs(ended, 5_000))
        assertNull(ActivityRules.nextChangeMs(ended, 5_000 + ActivityRules.DONE_MS))
        assertEquals(ended, ActivityRules.finish(ended, 99, 6_000, failed = true))
    }
}
