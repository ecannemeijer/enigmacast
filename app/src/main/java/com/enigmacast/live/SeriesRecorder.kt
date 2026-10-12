package com.enigmacast.live

import com.enigmacast.R
import com.enigmacast.data.db.CacheDao
import com.enigmacast.data.db.CacheMappers
import com.enigmacast.data.db.SeriesRuleDao
import com.enigmacast.data.db.SeriesRuleRow
import com.enigmacast.data.openwebif.OpenWebifApi
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.model.RecordingTarget
import com.enigmacast.domain.recording.RecordAsk
import com.enigmacast.domain.recording.Recording
import com.enigmacast.domain.recording.RecordingPlace
import com.enigmacast.domain.series.SeriesDetector
import com.enigmacast.domain.series.SeriesPlan
import com.enigmacast.domain.tuner.NowEvents
import com.enigmacast.util.EnigmaLog
import com.enigmacast.util.UiText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Record all upcoming": schedules every episode of a series on one channel that the cached
 * guide (8 days) shows, and keeps a rule so later syncs add new ones.
 *
 * On the box, the AutoTimer plugin is used when it is installed: it covers weeks the guide does
 * not reach yet, and the box creates the timers itself (the app then adds none, so nothing is
 * doubled). Without it, the app-side rule re-scans the guide after each sync.
 */
@Singleton
class SeriesRecorder @Inject constructor(
    private val dao: CacheDao,
    private val rulesDao: SeriesRuleDao,
    private val settings: SettingsRepository,
    private val api: OpenWebifApi,
    private val book: RecordingBook,
) {
    private val gate = Mutex()

    val rules: Flow<List<SeriesRuleRow>> = rulesDao.all()

    /** Later episodes of [event]'s series on the same channel, from the cached guide. */
    suspend fun upcoming(event: GuideEvent): List<GuideEvent> {
        val receiver = settings.current().activeReceiver() ?: return emptyList()
        val nowSec = System.currentTimeMillis() / 1000
        val rows = dao.serviceEvents(rk(receiver), NowEvents.key(event.reference), nowSec)
        val titleKey = titleKey(event.title)
        return rows.map { CacheMappers.event(it) }
            .filter { it.beginEpochSec != event.beginEpochSec && it.beginEpochSec > event.beginEpochSec - 60 && titleKey(it.title) == titleKey }
    }

    suspend fun recordAll(ask: RecordAsk): UiText = gate.withLock {
        val receiver = settings.current().activeReceiver() ?: return UiText.res(R.string.conn_no_receiver_configured)
        val title = SeriesDetector.cleanTitle(ask.event.title).ifBlank { ask.event.title }
        val existing = rulesDao.forReceiver(receiver.id).firstOrNull { it.titleKey == titleKey(ask.event.title) && it.serviceKey == NowEvents.key(ask.event.reference) }
        var autoTimerId: String? = existing?.autoTimerId
        if (ask.place == RecordingTarget.RECEIVER && autoTimerId == null && api.autoTimers(receiver) != null) {
            autoTimerId = api.addAutoTimer(receiver, title, title, ask.event.reference)
            EnigmaLog.service("series: autotimer for $title -> ${autoTimerId ?: "failed"}")
        }
        val rule = existing?.copy(autoTimerId = autoTimerId) ?: SeriesRuleRow(
            id = UUID.randomUUID().toString(),
            receiverId = receiver.id,
            titleKey = titleKey(ask.event.title),
            title = title,
            serviceKey = NowEvents.key(ask.event.reference),
            reference = ask.event.reference,
            channelName = ask.channelName,
            place = ask.place.name,
            autoTimerId = autoTimerId,
            createdMs = System.currentTimeMillis(),
        )
        rulesDao.put(rule)
        if (autoTimerId != null) {
            book.refreshReceiver()
            return UiText.res(R.string.record_all_autotimer, title)
        }
        val added = scheduleFor(receiver, rule, listOf(ask.event) + upcoming(ask.event))
        if (added <= 1 && upcoming(ask.event).isEmpty()) return UiText.res(R.string.record_all_none)
        UiText.res(R.string.record_all_done, added, title)
    }

    /** After a sync: new episodes for app-side rules of this receiver. */
    suspend fun rescan(receiver: ReceiverConfig) = gate.withLock {
        val nowSec = System.currentTimeMillis() / 1000
        for (rule in rulesDao.forReceiver(receiver.id)) {
            if (rule.autoTimerId != null) continue
            val events = dao.serviceEvents(rk(receiver), rule.serviceKey, nowSec).map { CacheMappers.event(it) }
                .filter { titleKey(it.title) == rule.titleKey && it.beginEpochSec > nowSec }
            val added = scheduleFor(receiver, rule, events)
            if (added > 0) EnigmaLog.service("series: ${rule.title} +$added")
        }
    }

    /** Schedules the [events] not yet recorded (app list and box timers); returns how many were added. */
    private suspend fun scheduleFor(receiver: ReceiverConfig, rule: SeriesRuleRow, events: List<GuideEvent>): Int {
        val place = if (rule.place == RecordingTarget.RECEIVER.name) RecordingTarget.RECEIVER else RecordingTarget.TV
        val boxTimers = if (place == RecordingTarget.RECEIVER) runCatching { api.timers(receiver) }.getOrDefault(emptyList()) else emptyList()
        val taken = book.items.value.filter { it.isActive() && it.receiverId == receiver.id }
            .map { SeriesPlan.Slot(NowEvents.key(it.reference), it.beginEpochSec) } +
            boxTimers.filter { it.state < 3 }.map { SeriesPlan.Slot(NowEvents.key(it.reference), it.begin) }
        val todo = SeriesPlan.missing(events.map { SeriesPlan.Slot(NowEvents.key(it.reference), it.beginEpochSec) to it }, taken)
        var added = 0
        for (event in todo) {
            book.schedule(RecordAsk(event, rule.channelName, place))
            added++
        }
        return added
    }

    /** Episodes (app recordings and box timers) that belong to [rule] or to the series [key]. */
    fun belongs(rule: SeriesRuleRow, title: String, reference: String): Boolean =
        titleKey(title) == rule.titleKey && NowEvents.key(reference) == rule.serviceKey

    /** Cancel all: future timers of the series, the AutoTimer and the rule. Recorded files stay. */
    suspend fun cancelAll(key: String, items: List<Recording>) = gate.withLock {
        val receiver = settings.current().activeReceiver() ?: return@withLock
        val nowSec = System.currentTimeMillis() / 1000
        rulesDao.forReceiver(receiver.id).filter { "${it.titleKey}|${it.serviceKey}" == key }.forEach { rule ->
            rule.autoTimerId?.takeIf { it.isNotBlank() }?.let { api.removeAutoTimer(receiver, it) }
            rulesDao.delete(rule.id)
        }
        items.filter { it.endEpochSec > nowSec && it.status != com.enigmacast.domain.recording.RecordingStatus.READY }.forEach { item ->
            if (item.id.startsWith(RecordingBook.BOX_TIMER)) book.deleteBox(item) else book.delete(item.id)
        }
        book.refreshReceiver()
    }

    private fun rk(receiver: ReceiverConfig) = CacheMappers.receiverKey(receiver.id, receiver.host, receiver.httpPort)

    companion object {
        fun titleKey(title: String): String = SeriesDetector.key(title, "").substringBeforeLast('|')
    }
}
