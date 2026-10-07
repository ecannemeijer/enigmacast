package com.enigmacast.live

import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.reminder.Reminder
import com.enigmacast.domain.reminder.ReminderStorage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReminderSchedulerTest {
    private val startMs = 1_800_000_000_000L
    private val startSec = startMs / 1000

    private class FakeStorage(var saved: List<Reminder> = emptyList()) : ReminderStorage {
        var saves = 0
        override suspend fun load(): List<Reminder> = saved
        override suspend fun save(reminders: List<Reminder>) {
            saved = reminders
            saves++
        }
    }

    private fun event(ref: String, beginSec: Long, title: String = "Show $ref") =
        GuideEvent(1, ref, title, "", "", beginSec, 1800)

    private fun reminder(ref: String, beginSec: Long, created: Long = 0) =
        Reminder(ref, "Channel $ref", 1, "Show $ref", beginSec, "rx", created)

    private fun TestScope.scheduler(storage: FakeStorage): Pair<ReminderScheduler, MutableList<Reminder>> {
        val scheduler = ReminderScheduler(storage, backgroundScope) { startMs + testScheduler.currentTime }
        val switched = mutableListOf<Reminder>()
        backgroundScope.launch { scheduler.switches.collect { switched += it } }
        runCurrent()
        return scheduler to switched
    }

    @Test
    fun noticeTenSecondsBeforeThenSwitchAtTheStart() = runTest {
        val storage = FakeStorage()
        val (scheduler, switched) = scheduler(storage)
        scheduler.toggle(event("a", startSec + 60), "NPO 1", "rx")
        runCurrent()
        assertTrue(scheduler.isScheduled(Reminder.keyOf("a", startSec + 60)))
        assertEquals(1, storage.saved.size)

        advanceTimeBy(49_000)
        runCurrent()
        assertNull(scheduler.notice.value)
        advanceTimeBy(1_000)
        runCurrent()
        val notice = scheduler.notice.value
        assertNotNull(notice)
        assertEquals(startSec + 60, notice!!.deadlineSec)
        assertTrue(switched.isEmpty())

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf("a"), switched.map { it.serviceReference })
        assertNull(scheduler.notice.value)
        assertTrue(scheduler.reminders.value.isEmpty())
        assertTrue(storage.saved.isEmpty())
    }

    @Test
    fun cancelOnTheNoticeDropsItWithoutSwitching() = runTest {
        val (scheduler, switched) = scheduler(FakeStorage())
        scheduler.schedule(reminder("a", startSec + 20))
        advanceTimeBy(10_500)
        runCurrent()
        assertNotNull(scheduler.notice.value)
        scheduler.dismissNotice()
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(switched.isEmpty())
        assertTrue(scheduler.reminders.value.isEmpty())
    }

    @Test
    fun switchNowSwitchesBeforeTheCountdownEnds() = runTest {
        val (scheduler, switched) = scheduler(FakeStorage())
        scheduler.schedule(reminder("a", startSec + 15))
        advanceTimeBy(6_000)
        runCurrent()
        scheduler.switchNow()
        runCurrent()
        assertEquals(1, switched.size)
    }

    @Test
    fun toggleOnAScheduledProgrammeCancelsIt() = runTest {
        val (scheduler, _) = scheduler(FakeStorage())
        val programme = event("a", startSec + 600)
        assertEquals(ReminderScheduler.Toggle.SET, scheduler.toggle(programme, "C", "rx"))
        assertEquals(ReminderScheduler.Toggle.CANCELLED, scheduler.toggle(programme, "C", "rx"))
        assertTrue(scheduler.scheduledKeys.value.isEmpty())
        assertEquals(ReminderScheduler.Toggle.ALREADY_BEGUN, scheduler.toggle(event("b", startSec), "C", "rx"))
    }

    @Test
    fun savedRemindersComeBackAndPastOnesAreDropped() = runTest {
        val storage = FakeStorage(listOf(reminder("past", startSec - 5), reminder("later", startSec + 30)))
        val (scheduler, switched) = scheduler(storage)
        assertEquals(listOf("later"), scheduler.reminders.value.map { it.serviceReference })
        assertEquals(listOf("later"), storage.saved.map { it.serviceReference })
        advanceTimeBy(31_000)
        runCurrent()
        assertEquals(listOf("later"), switched.map { it.serviceReference })
    }

    @Test
    fun twoAtOnceSwitchOnceAndCancelShowsTheOther() = runTest {
        val (scheduler, switched) = scheduler(FakeStorage())
        scheduler.schedule(reminder("first", startSec + 30, created = 1))
        scheduler.schedule(reminder("second", startSec + 30, created = 2))
        advanceTimeBy(20_500)
        runCurrent()
        val notice = scheduler.notice.value!!
        assertEquals("first", notice.reminder.serviceReference)
        assertEquals(listOf("second"), notice.alsoDue.map { it.serviceReference })
        scheduler.dismissNotice()
        runCurrent()
        assertEquals("second", scheduler.notice.value?.reminder?.serviceReference)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf("second"), switched.map { it.serviceReference })
        assertTrue(scheduler.reminders.value.isEmpty())
    }

    @Test
    fun switchingForOneOfTwoAtOnceDropsTheOther() = runTest {
        val (scheduler, switched) = scheduler(FakeStorage())
        scheduler.schedule(reminder("first", startSec + 30, created = 1))
        scheduler.schedule(reminder("second", startSec + 30, created = 2))
        scheduler.schedule(reminder("later", startSec + 3_600))
        advanceTimeBy(31_000)
        runCurrent()
        assertEquals(listOf("first"), switched.map { it.serviceReference })
        assertEquals(listOf("later"), scheduler.reminders.value.map { it.serviceReference })
    }

    @Test
    fun inTheBackgroundNothingShowsAndAReturnInTimeGetsAFreshCountdown() = runTest {
        val (scheduler, switched) = scheduler(FakeStorage())
        scheduler.schedule(reminder("a", startSec + 20))
        scheduler.setForeground(false)
        advanceTimeBy(40_000)
        runCurrent()
        assertNull(scheduler.notice.value)
        assertTrue(switched.isEmpty())
        scheduler.setForeground(true)
        runCurrent()
        val notice = scheduler.notice.value!!
        assertEquals(startSec + 45, notice.deadlineSec)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, switched.size)
    }

    @Test
    fun aReminderMissedForTwoMinutesInTheBackgroundIsDropped() = runTest {
        val (scheduler, switched) = scheduler(FakeStorage())
        scheduler.schedule(reminder("a", startSec + 20))
        scheduler.setForeground(false)
        advanceTimeBy(141_000)
        runCurrent()
        scheduler.setForeground(true)
        runCurrent()
        assertNull(scheduler.notice.value)
        assertTrue(scheduler.reminders.value.isEmpty())
        assertTrue(switched.isEmpty())
    }
}
