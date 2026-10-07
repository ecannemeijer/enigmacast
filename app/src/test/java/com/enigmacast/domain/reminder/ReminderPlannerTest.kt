package com.enigmacast.domain.reminder

import com.enigmacast.domain.epg.GuideEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderPlannerTest {
    private val begin = 1_800_000_000L

    private fun reminder(ref: String, beginSec: Long = begin, created: Long = 0, title: String = "Show") =
        Reminder(ref, "Channel $ref", 7, title, beginSec, "rx", created)

    @Test
    fun theKeyMatchesTheGuideProgrammeKey() {
        val event = GuideEvent(7, "1:0:19:283D:3FB:1:C00000:0:0:0:", "News", "", "", begin, 1800)
        val fromGuide = Reminder.of(event, "NPO 1", "rx", 5)
        assertEquals(event.programmeKey, fromGuide.key)
        // Same programme, reference written slightly differently (trailing name part).
        assertEquals(fromGuide.key, Reminder.keyOf("1:0:19:283D:3FB:1:C00000:0:0:0::NPO 1", begin))
    }

    @Test
    fun theNoticeIsDueTenSecondsBeforeTheStart() {
        val list = listOf(reminder("a"))
        assertNull(ReminderPlanner.due(list, begin - 11))
        assertEquals("a", ReminderPlanner.due(list, begin - 10)?.serviceReference)
        assertEquals(begin, ReminderPlanner.deadline(list.first(), begin - 10))
    }

    @Test
    fun aLateNoticeStillCountsDownFiveSeconds() {
        val late = reminder("a")
        assertEquals(begin + 35, ReminderPlanner.deadline(late, begin + 30))
        assertEquals(begin, ReminderPlanner.deadline(late, begin - 7))
        assertEquals(begin + 2, ReminderPlanner.deadline(late, begin - 3))
    }

    @Test
    fun remindersThatBeganWhileTheAppWasClosedAreDroppedOnStart() {
        val saved = listOf(reminder("past", begin - 60), reminder("now", begin), reminder("later", begin + 600), reminder("later", begin + 600))
        val kept = ReminderPlanner.onLoad(saved, begin)
        assertEquals(listOf("later"), kept.map { it.serviceReference })
    }

    @Test
    fun aReminderNotShownWithinTwoMinutesIsMissedUnlessItIsOnScreen() {
        val list = listOf(reminder("a"), reminder("b", begin + 600))
        assertTrue(ReminderPlanner.missed(list, begin + 119, null).isEmpty())
        assertEquals(listOf("a"), ReminderPlanner.missed(list, begin + 120, null).map { it.serviceReference })
        assertTrue(ReminderPlanner.missed(list, begin + 120, list.first().key).isEmpty())
        assertNull(ReminderPlanner.due(list, begin + 120))
    }

    @Test
    fun addingTheSameProgrammeTwiceKeepsOneAndTheListStaysInStartOrder() {
        var list = emptyList<Reminder>()
        list = ReminderPlanner.add(list, reminder("b", begin + 600))
        list = ReminderPlanner.add(list, reminder("a"))
        list = ReminderPlanner.add(list, reminder("a", title = "Renamed"))
        assertEquals(listOf("a", "b"), list.map { it.serviceReference })
        assertEquals("Renamed", list.first().title)
        assertEquals(listOf("b"), ReminderPlanner.remove(list, list.first().key).map { it.serviceReference })
        assertFalse(ReminderPlanner.canSchedule(begin, begin))
        assertTrue(ReminderPlanner.canSchedule(begin + 1, begin))
    }

    @Test
    fun twoAtTheSameTimeTheEarliestThenTheFirstSetWinsAndMentionsTheOther() {
        val first = reminder("first", created = 1)
        val second = reminder("second", created = 2)
        val slightlyEarlier = reminder("early", begin - 30, created = 3)
        val list = listOf(second, first)
        assertEquals("first", ReminderPlanner.due(list, begin - 5)?.serviceReference)
        val notice = ReminderPlanner.notice(list, first, begin - 5)
        assertEquals(listOf("second"), notice.alsoDue.map { it.serviceReference })
        val three = list + slightlyEarlier
        assertEquals("early", ReminderPlanner.due(three, begin - 5)?.serviceReference)
    }

    @Test
    fun switchingDropsTheOthersInTheSlotButNotLaterOnes() {
        val a = reminder("a", created = 1)
        val b = reminder("b", begin + 59, created = 2)
        val later = reminder("later", begin + 60)
        val left = ReminderPlanner.afterSwitch(listOf(a, b, later), a)
        assertEquals(listOf("later"), left.map { it.serviceReference })
    }
}
