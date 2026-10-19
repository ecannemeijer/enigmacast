package com.enigmacast.activity

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackgroundActivityTrackerTest {
    private class MemoryStore(var saved: SetupSession? = null) : SetupSessionStore {
        override fun load() = saved
        override fun save(session: SetupSession?) { saved = session }
    }

    private var now = 10_000L
    private val store = MemoryStore()
    private val tracker = BackgroundActivityTracker({ now }, store, null).also {
        // The existing window rules are tested inside a setup session with work still expected.
        it.beginInitialSetup(ActivityKind.entries.toSet())
    }
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

    // --- Setup session: the window only shows during the first sync after the wizard. ---

    private fun freshTracker(saved: SetupSession? = null) = MemoryStore(saved).let { it to BackgroundActivityTracker({ now }, it, null) }

    @Test fun withoutSessionNothingEverShows() {
        val (_, plain) = freshTracker()
        val id = plain.start(ActivityKind.SYNC_SERVER)
        now += 10_000
        assertNull(ActivityRules.view(plain.board.value, now))
        plain.finish(id)
        assertNull(ActivityRules.view(plain.board.value, now))
        // Tracking itself still works.
        assertTrue(plain.board.value.tasks.isEmpty())
    }

    @Test fun sessionEndsWhenAllExpectedWorkIsDoneAndSettled() {
        val (saved, t) = freshTracker()
        t.beginInitialSetup(setOf(ActivityKind.CHANNELS, ActivityKind.GUIDE))
        assertEquals(ActivityKind.CHANNELS, saved.saved!!.expected.first())
        val channels = t.start(ActivityKind.CHANNELS)
        now += 2_000
        t.finish(channels)
        val guide = t.start(ActivityKind.GUIDE)
        // Straight on from a shown task: no gap in the window.
        assertTrue(ActivityRules.view(t.board.value, now) is ActivityView.Running)
        now += 3_000
        t.finish(guide)
        assertEquals(ActivityView.Done, ActivityRules.view(t.board.value, now))
        assertEquals(setOf(ActivityKind.CHANNELS, ActivityKind.GUIDE), saved.saved!!.finished)
        now += ActivityRules.SETTLE_MS
        t.refresh()
        assertNull(t.board.value.session)
        assertNull(saved.saved)
        // Later work (a zap sync, a scheduled refresh) stays out of sight.
        val zap = t.start(ActivityKind.SYNC_SERVER)
        now += 20_000
        assertNull(ActivityRules.view(t.board.value, now))
        t.finish(zap)
    }

    @Test fun workAfterTheGuideKeepsTheSessionOpen() {
        val (_, t) = freshTracker()
        t.beginInitialSetup(setOf(ActivityKind.CHANNELS))
        val channels = t.start(ActivityKind.CHANNELS)
        now += 2_000
        t.finish(channels)
        now += 1_000
        val home = t.start(ActivityKind.HOME)
        now += ActivityRules.SETTLE_MS
        t.refresh()
        assertEquals(ActivityKind.HOME, (ActivityRules.view(t.board.value, now) as ActivityView.Running).kind)
        t.finish(home)
        now += ActivityRules.SETTLE_MS
        t.refresh()
        assertNull(t.board.value.session)
    }

    @Test fun missingExpectedWorkEndsAfterIdleStretch() {
        val (_, t) = freshTracker()
        t.beginInitialSetup(setOf(ActivityKind.CHANNELS, ActivityKind.IPTV))
        val channels = t.start(ActivityKind.CHANNELS)
        now += 2_000
        t.finish(channels)
        now += ActivityRules.SETTLE_MS
        t.refresh()
        assertTrue(t.board.value.session != null)
        now += ActivityRules.IDLE_END_MS
        assertNull(ActivityRules.view(t.board.value, now))
        t.refresh()
        assertNull(t.board.value.session)
    }

    @Test fun sessionNeverOutlivesTheTimeout() {
        val (saved, t) = freshTracker()
        t.beginInitialSetup(setOf(ActivityKind.CHANNELS))
        val stuck = t.start(ActivityKind.CHANNELS)
        now += ActivityRules.SESSION_TIMEOUT_MS - 1
        assertTrue(ActivityRules.view(t.board.value, now) is ActivityView.Running)
        now += 1
        assertNull(ActivityRules.view(t.board.value, now))
        t.refresh()
        assertNull(saved.saved)
        t.finish(stuck)
    }

    @Test fun restartMidSetupResumesUntilDone() {
        val session = SetupSession(startedMs = now - 30_000, expected = setOf(ActivityKind.CHANNELS, ActivityKind.GUIDE), finished = setOf(ActivityKind.CHANNELS), lastEndMs = now - 20_000)
        val (saved, t) = freshTracker(session)
        assertEquals(session, t.board.value.session)
        val guide = t.start(ActivityKind.GUIDE)
        now += 2_000
        assertTrue(ActivityRules.view(t.board.value, now) is ActivityView.Running)
        t.finish(guide)
        now += ActivityRules.SETTLE_MS
        t.refresh()
        assertNull(saved.saved)
    }

    @Test fun restartAfterTheTimeoutDropsTheOldSession() {
        val old = SetupSession(startedMs = now - ActivityRules.SESSION_TIMEOUT_MS - 1, expected = setOf(ActivityKind.CHANNELS))
        val (saved, t) = freshTracker(old)
        assertNull(t.board.value.session)
        assertNull(saved.saved)
    }

    @Test fun nextChangeIncludesTheSessionEnd() {
        val session = SetupSession(startedMs = 0, expected = setOf(ActivityKind.CHANNELS), finished = setOf(ActivityKind.CHANNELS), lastEndMs = 1_000)
        val board = ActivityBoard(session = session)
        assertEquals(1_000 + ActivityRules.SETTLE_MS, ActivityRules.nextChangeMs(board, 2_000))
        assertTrue(ActivityRules.sessionOver(session, idle = true, nowMs = 1_000 + ActivityRules.SETTLE_MS))
        assertTrue(!ActivityRules.sessionOver(session, idle = false, nowMs = 1_000 + ActivityRules.SETTLE_MS))
    }
}
