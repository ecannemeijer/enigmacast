package com.enigmacast.ui.tuners

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.data.openwebif.OpenWebifApi
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.PowerAction
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.recording.RecordingPlace
import com.enigmacast.domain.recording.RecordingStatus
import com.enigmacast.domain.sync.GuideCacheReader
import com.enigmacast.domain.tuner.RunningTimer
import com.enigmacast.domain.tuner.ScheduleItem
import com.enigmacast.domain.tuner.ScheduleKind
import com.enigmacast.domain.tuner.TodaySchedule
import com.enigmacast.domain.tuner.TunerBoard
import com.enigmacast.domain.tuner.TunerCard
import com.enigmacast.live.OwnTunerSession
import com.enigmacast.live.PiconRepository
import com.enigmacast.live.RecordingBook
import com.enigmacast.live.ReminderScheduler
import com.enigmacast.playback.LivePlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** Extra facts about the channel a tuner card shows. */
data class CardChannel(val bouquet: String = "", val now: GuideEvent? = null)

@HiltViewModel
class TunerHomeViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val api: OpenWebifApi,
    private val guideCache: GuideCacheReader,
    private val picons: PiconRepository,
    private val reminders: ReminderScheduler,
    private val recordings: RecordingBook,
    private val session: OwnTunerSession,
    private val playback: LivePlaybackController,
) : ViewModel() {
    var cards by mutableStateOf<List<TunerCard>>(emptyList()); private set
    var channelInfo by mutableStateOf<Map<String, CardChannel>>(emptyMap()); private set
    var schedule by mutableStateOf<List<ScheduleItem>>(emptyList()); private set
    var loading by mutableStateOf(true); private set
    var failed by mutableStateOf(false); private set
    var inStandby by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)

    /** Polls while Home is on screen (the caller's LaunchedEffect cancels it). */
    suspend fun whileVisible() {
        while (true) {
            refresh()
            delay(REFRESH_MS)
        }
    }

    suspend fun refresh() {
        val receiver = settingsRepository.current().activeReceiver()
        if (receiver == null) { loading = false; failed = true; return }
        val timers = runCatching { api.timers(receiver) }.getOrDefault(emptyList())
        val snapshot = runCatching { api.tunerSnapshot(receiver) }.getOrNull()
        if (snapshot == null) {
            failed = true
        } else {
            failed = false
            inStandby = snapshot.inStandby == true
            val running = timers.filter { it.state == 2 }.map { RunningTimer(it.reference, it.name) }
            cards = TunerBoard.cards(snapshot, ownAddresses(receiver), running)
            loadChannelInfo(receiver)
        }
        schedule = buildSchedule(receiver, timers)
        loading = false
    }

    private suspend fun loadChannelInfo(receiver: ReceiverConfig) {
        val nowSec = System.currentTimeMillis() / 1000
        val next = mutableMapOf<String, CardChannel>()
        for (ref in cards.flatMap { c -> c.uses.map { it.reference } }.filter { it.isNotBlank() }.distinct()) {
            val bouquet = runCatching { guideCache.bouquetOf(receiver, ref)?.name }.getOrNull().orEmpty()
            val events = runCatching { guideCache.serviceEvents(receiver, ref) }.getOrNull()
                ?.takeIf { list -> list.any { it.covers(nowSec) } }
                ?: runCatching { api.serviceEvents(receiver, ref) }.getOrDefault(emptyList())
            next[ref] = CardChannel(bouquet, events.firstOrNull { it.covers(nowSec) })
        }
        channelInfo = next
    }

    private fun GuideEvent.covers(nowSec: Long) = beginEpochSec <= nowSec && beginEpochSec + durationSec > nowSec

    private suspend fun buildSchedule(receiver: ReceiverConfig, timers: List<com.enigmacast.data.openwebif.OpenWebifJson.ReceiverTimer>): List<ScheduleItem> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val start = today.atStartOfDay(zone).toEpochSecond()
        val end = today.plusDays(1).atStartOfDay(zone).toEpochSecond()
        val nowSec = System.currentTimeMillis() / 1000
        val ours = recordings.items.value.filter { it.isActive() }
        val items = mutableListOf<ScheduleItem>()
        ours.forEach { r ->
            items += ScheduleItem(
                r.beginEpochSec, r.endEpochSec, r.channelName, r.title,
                if (r.place == RecordingPlace.TV) ScheduleKind.TV_RECORDING else ScheduleKind.BOX_RECORDING,
                running = r.status == RecordingStatus.RECORDING,
            )
        }
        timers.filter { it.state < 3 }
            .filter { t -> ours.none { it.place == RecordingPlace.RECEIVER && it.beginEpochSec == t.begin && it.title.equals(t.name, true) } }
            .forEach { t ->
                val channel = channelName(receiver, t.reference)
                items += ScheduleItem(t.begin, t.end, channel, t.name, ScheduleKind.BOX_TIMER, running = t.state == 2)
            }
        reminders.reminders.value.filter { it.receiverId.isBlank() || it.receiverId == receiver.id }.forEach { r ->
            items += ScheduleItem(r.beginEpochSec, r.beginEpochSec, r.channelName, r.title, ScheduleKind.AUTOSWITCH)
        }
        return TodaySchedule.merge(items, start, end, nowSec)
    }

    private val channelNames = mutableMapOf<String, String>()

    private suspend fun channelName(receiver: ReceiverConfig, reference: String): String {
        channelNames[reference]?.let { return it }
        val name = runCatching { guideCache.bouquetOf(receiver, reference) }.getOrNull()?.let { b ->
            guideCache.channelsInBouquet(receiver, b.reference)?.firstOrNull { it.reference == reference }?.name
        }.orEmpty()
        channelNames[reference] = name
        return name
    }

    fun cachedPicon(reference: String, name: String): ImageBitmap? = picons.cached(BouquetEntry(name, reference))

    suspend fun picon(reference: String, name: String): ImageBitmap? {
        val receiver = settingsRepository.current().activeReceiver() ?: return null
        return runCatching { picons.load(receiver, BouquetEntry(name, reference)) }.getOrNull()
    }

    /** Stops this app's own stream (the tuner frees up on the box within a few seconds). */
    fun stopOwnStream() {
        session.deactivate()
        playback.stop()
        viewModelScope.launch { delay(1500); refresh() }
    }

    /** Puts the receiver in standby: its live picture stops and that tuner is freed. */
    fun standby() {
        viewModelScope.launch {
            val receiver = settingsRepository.current().activeReceiver() ?: return@launch
            runCatching { api.setPowerState(receiver, PowerAction.STANDBY) }
            delay(1500)
            refresh()
        }
    }

    private suspend fun ownAddresses(receiver: ReceiverConfig): Set<String> = withContext(Dispatchers.IO) {
        val addresses = mutableSetOf<String>()
        runCatching {
            DatagramSocket().use { socket ->
                socket.connect(InetSocketAddress(receiver.host, receiver.streamingPort))
                socket.localAddress?.hostAddress?.let(addresses::add)
            }
        }
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .mapNotNull { it.hostAddress }
                .forEach(addresses::add)
        }
        addresses
    }

    private companion object {
        const val REFRESH_MS = 15_000L
    }
}
