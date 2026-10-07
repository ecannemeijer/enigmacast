package com.enigmacast.live

import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.reminder.Reminder
import com.enigmacast.domain.reminder.ReminderNotice
import com.enigmacast.domain.reminder.ReminderPlanner
import com.enigmacast.domain.reminder.ReminderStorage
import com.enigmacast.util.EnigmaLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs AutoSwitch while the app is in the foreground: shows [notice] ten seconds before a
 * programme begins and emits on [switches] when the countdown ends (or on "Switch now").
 * Reminders are saved, so they survive a restart; nothing runs while the app is closed or in
 * the background (no alarm or background service). See [ReminderPlanner] for the rules.
 */
class ReminderScheduler(
    private val storage: ReminderStorage,
    private val scope: CoroutineScope,
    private val clockMs: () -> Long,
) {
    private val _reminders = MutableStateFlow<List<Reminder>>(emptyList())
    val reminders: StateFlow<List<Reminder>> = _reminders.asStateFlow()

    private val _scheduledKeys = MutableStateFlow<Set<String>>(emptySet())

    /** Programmes with AutoSwitch set, as [Reminder.key] (same as `GuideEvent.programmeKey`). */
    val scheduledKeys: StateFlow<Set<String>> = _scheduledKeys.asStateFlow()

    private val _notice = MutableStateFlow<ReminderNotice?>(null)
    val notice: StateFlow<ReminderNotice?> = _notice.asStateFlow()

    private val _switches = MutableSharedFlow<Reminder>(extraBufferCapacity = 4)
    val switches: SharedFlow<Reminder> = _switches.asSharedFlow()

    private var loaded = false
    private var foreground = true
    private var loop: Job? = null

    init {
        scope.launch { load() }
    }

    fun isScheduled(key: String): Boolean = key in _scheduledKeys.value

    /** Sets AutoSwitch for [reminder]; false when the programme already began. */
    fun schedule(reminder: Reminder): Boolean {
        if (!ReminderPlanner.canSchedule(reminder.beginEpochSec, nowSec())) return false
        publish(ReminderPlanner.add(_reminders.value, reminder))
        EnigmaLog.service("autoswitch set for ${reminder.beginEpochSec}")
        wake()
        return true
    }

    enum class Toggle { SET, CANCELLED, ALREADY_BEGUN }

    /** AutoSwitch from the guide: set it for [event], or cancel it when it was set. */
    fun toggle(event: GuideEvent, channelName: String, receiverId: String): Toggle {
        val key = event.programmeKey
        if (isScheduled(key)) {
            cancel(key)
            return Toggle.CANCELLED
        }
        return if (schedule(Reminder.of(event, channelName, receiverId, clockMs()))) Toggle.SET else Toggle.ALREADY_BEGUN
    }

    fun cancel(key: String) {
        if (_notice.value?.reminder?.key == key) _notice.value = null
        if (_reminders.value.none { it.key == key }) return
        publish(ReminderPlanner.remove(_reminders.value, key))
        EnigmaLog.service("autoswitch cancelled")
        wake()
    }

    /** "Switch now" on the notice. */
    fun switchNow() {
        val shown = _notice.value ?: return
        fire(shown.reminder)
    }

    /** Cancel or Back on the notice: this reminder is dropped; another one due now shows next. */
    fun dismissNotice() {
        val shown = _notice.value ?: return
        _notice.value = null
        publish(ReminderPlanner.remove(_reminders.value, shown.reminder.key))
        EnigmaLog.service("autoswitch notice cancelled")
        wake()
    }

    /**
     * The app went to the background or came back. In the background no notice shows and nothing
     * switches; a notice on screen comes back (with a fresh countdown) when the app returns in time.
     */
    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (!value) _notice.value = null
        wake()
    }

    private suspend fun load() {
        val saved = try {
            storage.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            EnigmaLog.service("autoswitch load failed: ${error.javaClass.simpleName}")
            emptyList()
        }
        val kept = ReminderPlanner.onLoad(saved, nowSec())
        // Reminders set before loading finished stay too.
        val merged = _reminders.value.fold(kept) { list, reminder -> ReminderPlanner.add(list, reminder) }
        loaded = true
        if (saved.size != kept.size) EnigmaLog.service("autoswitch: ${saved.size - kept.size} past reminder(s) dropped")
        if (merged != saved) publish(merged) else _reminders.value = merged.also { _scheduledKeys.value = it.keys() }
        wake()
    }

    private fun wake() {
        loop?.cancel()
        loop = scope.launch {
            while (isActive) {
                tick()
                delay(nextDelayMs())
            }
        }
    }

    /** One look at the clock: drop missed reminders, show a due notice, switch at its deadline. */
    internal fun tick() {
        if (!loaded) return
        val now = nowSec()
        val shown = _notice.value
        val missed = ReminderPlanner.missed(_reminders.value, now, shown?.reminder?.key)
        if (missed.isNotEmpty()) {
            EnigmaLog.service("autoswitch: ${missed.size} missed reminder(s) dropped")
            publish(_reminders.value - missed.toSet())
        }
        if (!foreground) return
        if (shown != null) {
            if (now >= shown.deadlineSec) {
                fire(shown.reminder)
            } else {
                _notice.value = shown.copy(alsoDue = ReminderPlanner.sameSlot(_reminders.value, shown.reminder))
            }
            return
        }
        val due = ReminderPlanner.due(_reminders.value, now) ?: return
        _notice.value = ReminderPlanner.notice(_reminders.value, due, now)
        EnigmaLog.service("autoswitch notice shown")
    }

    private fun fire(reminder: Reminder) {
        _notice.value = null
        publish(ReminderPlanner.afterSwitch(_reminders.value, reminder))
        EnigmaLog.service("autoswitch switching")
        _switches.tryEmit(reminder)
    }

    private fun nextDelayMs(): Long {
        if (!loaded) return IDLE_MS
        if (_notice.value != null) return COUNTDOWN_TICK_MS
        if (!foreground) return IDLE_MS
        val nowMs = clockMs()
        val nextMs = _reminders.value.minOfOrNull { (it.beginEpochSec - ReminderPlanner.NOTICE_SEC) * 1000 } ?: return IDLE_MS
        return (nextMs - nowMs).coerceIn(COUNTDOWN_TICK_MS, IDLE_MS)
    }

    private fun publish(list: List<Reminder>) {
        _reminders.value = list
        _scheduledKeys.value = list.keys()
        scope.launch {
            try {
                storage.save(list)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                EnigmaLog.service("autoswitch save failed: ${error.javaClass.simpleName}")
            }
        }
    }

    private fun List<Reminder>.keys(): Set<String> = mapTo(HashSet()) { it.key }

    private fun nowSec(): Long = clockMs() / 1000

    companion object {
        const val COUNTDOWN_TICK_MS = 250L
        const val IDLE_MS = 30_000L
    }
}
