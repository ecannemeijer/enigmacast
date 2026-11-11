package com.enigmacast.domain.reminder

import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.tuner.NowEvents
import kotlinx.serialization.Serializable

@Serializable
enum class ReminderKind { AUTO_SWITCH, REMIND }

/**
 * AutoSwitch: switch to [serviceReference] when the programme [title] begins at [beginEpochSec].
 * Ten seconds before, a notice counts down; then the app zaps unless the viewer cancels.
 */
@Serializable
data class Reminder(
    val serviceReference: String,
    val channelName: String,
    val eventId: Int,
    val title: String,
    val beginEpochSec: Long,
    /** The receiver whose channel list the reminder came from; blank for any. */
    val receiverId: String = "",
    val createdAtMs: Long = 0,
) {
    /** Same key as [GuideEvent.programmeKey], so both guide views can mark the programme. */
    val key: String get() = keyOf(serviceReference, beginEpochSec)

    companion object {
        fun keyOf(reference: String, beginEpochSec: Long): String = "${NowEvents.key(reference)}|$beginEpochSec"

        fun of(event: GuideEvent, channelName: String, receiverId: String, nowMs: Long, kind: ReminderKind = ReminderKind.AUTO_SWITCH, providerId: String = "): Reminder = Reminder(
            serviceReference = event.reference,
            channelName = channelName,
            eventId = event.eventId,
            title = event.title,
            beginEpochSec = event.beginEpochSec,
            receiverId = receiverId,
            createdAtMs = nowMs,
            kind = kind,
            providerId = providerId,
        )
    }
}

/** The countdown on screen: [reminder] switches at [deadlineSec]; [alsoDue] begin at about the same time. */
data class ReminderNotice(
    val reminder: Reminder,
    val deadlineSec: Long,
    val alsoDue: List<Reminder> = emptyList(),
)

/**
 * The rules behind AutoSwitch, free of clocks and storage so they can be tested.
 *
 * - The notice appears [NOTICE_SEC] seconds before the programme begins and switches when it begins.
 *   A notice that appears late (the app was busy or just started) still counts down at least
 *   [MIN_COUNTDOWN_SEC] seconds, so the viewer can always cancel.
 * - A reminder that could not be shown until [MISSED_AFTER_SEC] after its start is dropped.
 * - Saved reminders whose programme began while the app was closed are dropped on start.
 * - Two reminders beginning within [SAME_SLOT_SEC] of each other are in the same slot: the earliest
 *   (then the one set first) gets the notice, which mentions the others. Switching drops the
 *   others in the slot (one channel can play at a time); cancelling shows the next one.
 */
object ReminderPlanner {
    const val NOTICE_SEC = 10L
    const val MIN_COUNTDOWN_SEC = 5L
    const val MISSED_AFTER_SEC = 120L
    /** A reminder notice stays this long after the start so Watch still works. */
    const val REMIND_KEEP_SEC = 30 * 60L
    const val SAME_SLOT_SEC = 60L

    private val order = compareBy<Reminder>({ it.beginEpochSec }, { it.createdAtMs })

    /** Saved reminders at app start: programmes that already began are gone. */
    fun onLoad(saved: List<Reminder>, nowSec: Long): List<Reminder> =
        saved.filter { keepOnLoad(it, nowSec) }.distinctBy { it.key }.sortedWith(order)

    fun keepOnLoad(reminder: Reminder, nowSec: Long): Boolean = when (reminder.kind) {
        ReminderKind.REMIND -> nowSec < reminder.beginEpochSec + REMIND_KEEP_SEC
        ReminderKind.AUTO_SWITCH -> reminder.beginEpochSec > nowSec
    }

    fun missedAfter(reminder: Reminder): Long =
        if (reminder.kind == ReminderKind.REMIND) REMIND_KEEP_SEC else MISSED_AFTER_SEC

    /** Adds [reminder], replacing one for the same programme. */
    fun add(reminders: List<Reminder>, reminder: Reminder): List<Reminder> =
        (reminders.filterNot { it.key == reminder.key } + reminder).sortedWith(order)

    fun remove(reminders: List<Reminder>, key: String): List<Reminder> = reminders.filterNot { it.key == key }

    /** Programmes that could be scheduled: still to begin. */
    fun canSchedule(beginEpochSec: Long, nowSec: Long): Boolean = beginEpochSec > nowSec

    /** Reminders whose moment passed without a notice (not the one on screen). */
    fun missed(reminders: List<Reminder>, nowSec: Long, shownKey: String?): List<Reminder> =
        reminders.filter { it.key != shownKey && nowSec >= it.beginEpochSec + missedAfter(it) }

    /** The reminder whose notice should show now, if any. */
    fun due(reminders: List<Reminder>, nowSec: Long): Reminder? = reminders
        .filter { nowSec >= it.beginEpochSec - NOTICE_SEC && nowSec < it.beginEpochSec + missedAfter(it) }
        .minWithOrNull(order)

    /** Other reminders in [reminder]'s slot. */
    fun sameSlot(reminders: List<Reminder>, reminder: Reminder): List<Reminder> = reminders
        .filter { it.key != reminder.key && kotlin.math.abs(it.beginEpochSec - reminder.beginEpochSec) < SAME_SLOT_SEC }
        .sortedWith(order)

    /** When the notice that appears at [shownAtSec] switches. */
    fun deadline(reminder: Reminder, shownAtSec: Long): Long =
        if (reminder.kind == ReminderKind.REMIND) reminder.beginEpochSec + REMIND_KEEP_SEC
        else maxOf(reminder.beginEpochSec, shownAtSec + MIN_COUNTDOWN_SEC)

    fun notice(reminders: List<Reminder>, reminder: Reminder, shownAtSec: Long): ReminderNotice =
        ReminderNotice(reminder, deadline(reminder, shownAtSec), sameSlot(reminders, reminder))

    /** After switching for [switched]: it and the others in its slot are done. */
    fun afterSwitch(reminders: List<Reminder>, switched: Reminder): List<Reminder> {
        val slot = sameSlot(reminders, switched).map { it.key }.toSet() + switched.key
        return reminders.filterNot { it.key in slot }
    }
}
