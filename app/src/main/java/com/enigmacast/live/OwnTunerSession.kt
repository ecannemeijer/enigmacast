package com.enigmacast.live

import com.enigmacast.R
import com.enigmacast.data.openwebif.OpenWebifEndpoints
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ChannelGuide
import com.enigmacast.domain.model.ChannelMemory
import com.enigmacast.domain.model.LastChannel
import com.enigmacast.domain.model.LastLiveSource
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.model.StartupLive
import com.enigmacast.domain.model.StreamMode
import com.enigmacast.domain.model.ReceiverList
import com.enigmacast.domain.service.BouquetChannels
import com.enigmacast.domain.service.ServiceStreams
import com.enigmacast.domain.service.StreamReuse
import com.enigmacast.domain.source.ServiceStateSource
import com.enigmacast.domain.source.TunerStatusSource
import com.enigmacast.domain.tuner.ChannelNavigator
import com.enigmacast.domain.tuner.PlannedRecording
import com.enigmacast.domain.tuner.RecordingGuard
import com.enigmacast.domain.tuner.ZapDecision
import com.enigmacast.domain.tuner.RestartBudget
import com.enigmacast.domain.tuner.StreamHealth
import com.enigmacast.domain.tuner.TunerAvailability
import com.enigmacast.domain.tuner.TunerSnapshot
import com.enigmacast.domain.tuner.TunerUser
import com.enigmacast.domain.tuner.TunerVerdict
import com.enigmacast.playback.LivePlaybackController
import com.enigmacast.util.EnigmaLog
import com.enigmacast.util.UiText
import com.enigmacast.util.isEmpty
import com.enigmacast.util.isNotEmpty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

enum class TunerStatus { IDLE, CHECKING, STARTING, PLAYING, FULL, NO_PICTURE, PROBLEM }

data class TunerState(
    val status: TunerStatus = TunerStatus.IDLE,
    val message: UiText = UiText.Empty,
    val used: Int = 0,
    val total: Int = 0,
    val users: List<TunerUser> = emptyList(),
    /** The receiver was unreachable; try again by itself once it may be back. Never set for full tuners. */
    val autoRetry: Boolean = false,
)

data class OwnTunerState(
    val channel: BouquetEntry? = null,
    val channels: List<BouquetEntry> = emptyList(),
    val bouquetName: String = "",
    val bouquetReference: String = "",
    val guide: ChannelGuide? = null,
    val tuner: TunerState = TunerState(),
    /** Bumped on every zap so screens can show the channel banner. */
    val zapCount: Int = 0,
    /** The first channel list is still being fetched. Later zaps do not set this. */
    val loadingChannels: Boolean = false,
    /** What the wait dialog is fetching: bouquets, channels or programme information. */
    val loadingLabel: UiText = UiText.Empty,
    /** Share of those steps already finished, from 0 to 1. The receiver does not report a finer figure. */
    val loadingProgress: Float = 0f,
)

/**
 * Viewing with an own tuner: this device asks the receiver's stream server for a channel
 * directly, without zapping the receiver. Several devices can do this at once, one tuner
 * each (or sharing one when their channels are on the same transponder). Before a stream
 * starts, the receiver's tuner use is checked; when all tuners are busy the stream is not
 * started and the screen says so. A refusal the check could not foresee is caught when
 * the stream ends without a picture.
 */
@Singleton
class OwnTunerSession @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val serviceSource: ServiceStateSource,
    private val tunerSource: TunerStatusSource,
    private val playback: LivePlaybackController,
    private val endpoints: OpenWebifEndpoints,
    private val streamTargets: StreamTargets,
    private val quality: StreamQualityController,
    private val premiumGate: PremiumGate,
    private val listEvents: com.enigmacast.sync.ChannelListEvents,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        // New lists on the box (settings import): the zap list follows without a restart; the channel keeps playing.
        scope.launch { listEvents.changed.collect { id -> if (id == boundReceiverId) reloadChannelList() } }
    }

    /** Re-reads the shown bouquet's channels from the (fresh) cache. */
    private suspend fun reloadChannelList() {
        val reference = _state.value.bouquetReference
        if (reference.isBlank()) return
        val receiver = settingsRepository.current().activeReceiver() ?: return
        if (receiver.id != boundReceiverId) return
        val channels = runCatching { serviceSource.channelsInBouquet(receiver, reference) }.getOrElse { error ->
            if (error is CancellationException) throw error
            return
        }
        if (channels.isEmpty()) return
        _state.update { it.copy(channels = channels) }
        EnigmaLog.service("channel list reloaded after list change: ${channels.size}")
    }
    private val _state = MutableStateFlow(OwnTunerState())
    val state: StateFlow<OwnTunerState> = _state.asStateFlow()
    private var startJob: Job? = null
    private var guideJob: Job? = null
    private var token = 0
    /** The box whose channel list is in [state]. A different id clears that list. */
    private var boundReceiverId: String? = null
    private var lastSnapshot: TunerSnapshot? = null
    private var lastOwnAddresses: Set<String> = emptySet()
    private var streaming = false
    private var active = false
    private val restarts = RestartBudget()
    private var gaveUp = false
    /** The box the user zapped on. Failover walks the others and comes back here when every box is full. */
    private var failoverOriginId: String? = null
    /**
     * Box the user picked in Switch receiver. While set, a full box does not hand the stream to
     * another box (that would undo the choice); it clears once a stream plays there.
     */
    private var pinnedReceiverId: String? = null
    private var failoverTried: Set<String> = emptySet()

    init {
        scope.launch {
            playback.health.collect(::onHealth)
        }
        // A new quality reopens the channel: at once when the user chose it, otherwise
        // (stalls, stable stretch, transcoder fallback) within the automatic restart budget.
        scope.launch {
            settingsRepository.settings
                .map { it.activeReceiver()?.id }
                .distinctUntilChanged()
                .collect { bind(it) }
        }
        scope.launch {
            quality.restarts.collect { restart ->
                if (!active || playback.isFilePlayback() || _state.value.channel == null) return@collect
                if (restart.manual) retry() else restartAutomatically("quality")
            }
        }
    }

    private var startAfterLoad = true

    private val _notices = MutableSharedFlow<UiText>(extraBufferCapacity = 4)
    /** Short messages for the picture (recording switch, blocked zap). */
    val notices: SharedFlow<UiText> = _notices.asSharedFlow()

    /** Recordings running on the active box now (set by [RecordingTunerGuard]). */
    @Volatile var runningRecordings: List<PlannedRecording> = emptyList()

    /** The DVB service this device is streaming, or null when it streams nothing from a tuner. */
    fun streamingReference(): String? =
        _state.value.channel?.reference?.takeIf { active && streaming && !playback.isFilePlayback() }

    /** Channel name from the loaded list, for messages. */
    fun channelNameFor(reference: String, fallback: String): String =
        _state.value.channels.firstOrNull { BouquetChannels.sameService(it.reference, reference) }?.name ?: fallback

    suspend fun addressesFor(receiver: ReceiverConfig): Set<String> = ownAddresses(receiver)

    /** A recording needs this device's tuner: show why, then stream the recording's service. */
    fun switchForRecording(recording: PlannedRecording) {
        val entry = _state.value.channels.firstOrNull { BouquetChannels.sameService(it.reference, recording.reference) }
            ?: BouquetEntry(recording.channelName, recording.reference)
        EnigmaLog.service("recording needs the tuner: switching to ${entry.name}")
        _notices.tryEmit(UiText.res(R.string.recording_switch_notice, entry.name))
        start(entry)
    }

    private val _conflict = MutableStateFlow<com.enigmacast.domain.tuner.TunerConflict?>(null)
    /** No tuner for the requested channel because of running recordings: the dialog offers Watch / Cancel. */
    val conflict: StateFlow<com.enigmacast.domain.tuner.TunerConflict?> = _conflict.asStateFlow()

    fun setConflict(value: com.enigmacast.domain.tuner.TunerConflict?) { _conflict.value = value }

    /** Reads the running recordings from the box now (set by [RecordingTunerGuard]). */
    var fetchRunningRecordings: (suspend () -> List<PlannedRecording>)? = null

    /** The running recordings, read from the box at once rather than from the last 30 s poll. */
    private suspend fun freshRunning(): List<PlannedRecording> {
        val fresh = runCatching { fetchRunningRecordings?.invoke() }.getOrNull()
        if (fresh != null) runningRecordings = fresh
        return runningRecordings.ifEmpty {
            com.enigmacast.domain.tuner.RecordingConflicts.fromSnapshot(lastSnapshot) { channelNameFor(it, it) }
        }
    }

    /** A stream did not start: when recordings run on other transponders, offer them instead of "no tuner free". */
    private var conflictRaisedAtZap = -1

    private suspend fun conflictAfterFailure(channel: BouquetEntry, health: StreamHealth): Boolean {
        val zap = _state.value.zapCount
        if (conflictRaisedAtZap == zap) return true
        val running = freshRunning()
        val blockers = com.enigmacast.domain.tuner.RecordingConflicts.afterStreamFailure(health, running, channel.reference)
        EnigmaLog.service("stream failure $health on ${channel.name}: running=${running.size} blockers=${blockers.size}")
        if (blockers.isEmpty()) return false
        conflictRaisedAtZap = zap
        // Stop retrying a stream that cannot get a tuner; the dialog decides what comes next.
        streaming = false
        playback.stopForZap()
        _conflict.value = com.enigmacast.domain.tuner.TunerConflict(channel, blockers)
        _state.update { it.copy(tuner = TunerState(TunerStatus.FULL, blockedMessage(blockers.first()))) }
        return true
    }

    private fun raiseConflict(channel: BouquetEntry): Boolean {
        val blockers = com.enigmacast.domain.tuner.RecordingConflicts.blockers(runningRecordings, channel.reference)
        if (blockers.isEmpty()) return false
        _conflict.value = com.enigmacast.domain.tuner.TunerConflict(channel, blockers)
        return true
    }

    private fun blockedMessage(recording: PlannedRecording): UiText =
        UiText.res(R.string.recording_zap_blocked, channelNameFor(recording.reference, recording.channelName))

    /**
     * The active box changed. Drop the previous channel list so the next start
     * loads this box's bouquet instead of zapping the old service reference.
     */
    fun bind(receiverId: String?) {
        if (receiverId == boundReceiverId) return
        val switching = boundReceiverId != null
        boundReceiverId = receiverId
        if (!switching) return
        abandonCurrent()
        if (receiverId != null && active) loadInitial(startPlayback = !playback.isFilePlayback())
    }

    private fun abandonCurrent() {
        token += 1
        restarts.reset()
        gaveUp = false
        startJob?.cancel()
        guideJob?.cancel()
        if (streaming) playback.stopForZap()
        streaming = false
        _state.value = OwnTunerState()
    }

    /** Starts the last watched channel of the active box, or that box's current channel. */
    fun ensureStarted() {
        active = true
        if (startJob?.isActive == true) return
        scope.launch {
            val settings = settingsRepository.current()
            val id = settings.activeReceiver()?.id
            // IPTV startup leaves the decoder free. Satellite startup plays the saved channel
            // even when the last picture was IPTV. Off keeps the previous rule.
            val play = settings.streamMode == StreamMode.OWN_TUNER && when (settings.startupLive) {
                StartupLive.IPTV -> false
                StartupLive.SATELLITE -> true
                StartupLive.OFF -> settings.lastLiveSource != LastLiveSource.IPTV
            }
            if (id != boundReceiverId) {
                val switching = boundReceiverId != null
                bind(id)
                if (switching) return@launch
            }
            if (playback.isFilePlayback()) {
                if (_state.value.channels.isEmpty()) loadInitial(startPlayback = false)
                return@launch
            }
            val current = _state.value
            // A remembered service is not playable until this box's channel list is in.
            // Starting earlier opens a stream the receiver rejects, which shows as an error.
            if (current.channels.isEmpty()) {
                loadInitial(startPlayback = play)
                return@launch
            }
            if (!play) return@launch
            if (current.channel != null) {
                if (current.tuner.status == TunerStatus.IDLE) start(current.channel)
                return@launch
            }
            current.channels.firstOrNull()?.let(::start)
        }
    }

    /** The recording stopped: play the channel that was on, or load the list and start one. */
    fun resumeAfterFile() {
        active = true
        if (startJob?.isActive == true && _state.value.channels.isEmpty()) {
            startAfterLoad = true
            return
        }
        val channel = _state.value.channel
        if (channel != null) start(channel, force = true) else ensureStarted()
    }

    private var channelLoadFailed = false

    private fun showLoad(label: UiText, progress: Float) {
        _state.update {
            it.copy(
                loadingChannels = true,
                loadingLabel = label,
                loadingProgress = progress.coerceIn(0f, 1f),
                tuner = TunerState(TunerStatus.CHECKING, label),
            )
        }
    }

    private fun hideLoad() {
        if (!_state.value.loadingChannels && _state.value.loadingLabel.isEmpty()) return
        _state.update { it.copy(loadingChannels = false, loadingLabel = UiText.Empty, loadingProgress = 0f) }
    }

    private fun loadInitial(startPlayback: Boolean) {
        startAfterLoad = startPlayback
        val dialog = !channelLoadFailed
        startJob = scope.launch {
            if (dialog) showLoad(UiText.res(R.string.load_channels), 0.05f)
            try {
                val settings = settingsRepository.current()
                val receiver = settings.activeReceiver() ?: return@launch
                boundReceiverId = receiver.id
                val last = ChannelMemory.lastFor(settings, receiver.id)
                val list = runCatching {
                    if (last.bouquetReference.isNotBlank()) {
                        if (dialog) showLoad(UiText.res(R.string.load_channels), 0.12f)
                        val channels = runCatching { serviceSource.channelsInBouquet(receiver, last.bouquetReference) }
                            .getOrElse { error ->
                                if (error is CancellationException) throw error
                                EnigmaLog.service("remembered bouquet failed, loading the current bouquet")
                                null
                            }
                        if (channels != null) {
                            if (dialog) showLoad(UiText.res(R.string.load_bouquets), 0.45f)
                            val name = runCatching {
                                serviceSource.televisionBouquets(receiver) + serviceSource.radioBouquets(receiver)
                            }.getOrDefault(emptyList()).firstOrNull { it.reference == last.bouquetReference }?.name.orEmpty()
                            Triple(name, last.bouquetReference, channels)
                        } else {
                            if (dialog) showLoad(UiText.res(R.string.load_bouquets), 0.2f)
                            val current = serviceSource.currentBouquetChannels(receiver)
                            Triple(current.bouquetName, current.bouquetReference, current.channels)
                        }
                    } else {
                        if (dialog) showLoad(UiText.res(R.string.load_bouquets), 0.15f)
                        val current = serviceSource.currentBouquetChannels(receiver)
                        Triple(current.bouquetName, current.bouquetReference, current.channels)
                    }
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    channelLoadFailed = true
                    EnigmaLog.service("channel list failed: ${error.javaClass.simpleName}")
                    _state.update { it.copy(tuner = TunerState(TunerStatus.PROBLEM, UiText.res(R.string.tuner_channels_failed))) }
                    return@launch
                }
                val (bouquetName, bouquetReference, channels) = list
                val receiverChannel = if (last.reference.isBlank()) {
                    runCatching { serviceSource.currentService(receiver).reference }.getOrNull().orEmpty()
                } else {
                    ""
                }
                val target = channels.firstOrNull { BouquetChannels.sameService(it.reference, last.reference) }
                    ?: channels.firstOrNull { BouquetChannels.sameService(it.reference, receiverChannel) }
                    ?: channels.firstOrNull()
                channelLoadFailed = false
                _state.update {
                    it.copy(
                        channels = channels,
                        bouquetName = bouquetName,
                        bouquetReference = bouquetReference,
                    )
                }
                if (dialog && target != null) {
                    showLoad(UiText.res(R.string.load_programme), 0.75f)
                    runCatching { serviceSource.channelGuide(receiver, target.reference, target.name) }
                        .onSuccess { guide -> _state.update { it.copy(guide = guide) } }
                        .onFailure { error ->
                            if (error is CancellationException) throw error
                            EnigmaLog.service("programme load failed: ${error.javaClass.simpleName}")
                        }
                }
                hideLoad()
                if (!startAfterLoad || playback.isFilePlayback()) return@launch
                if (target == null) {
                    _state.update { it.copy(tuner = TunerState(TunerStatus.PROBLEM, UiText.res(R.string.tuner_no_channels))) }
                    return@launch
                }
                beginFailover(receiver.id)
                startNow(receiver, target)
            } finally {
                hideLoad()
            }
        }
    }

    /**
     * Zap this device's stream to [channel]; [channels] becomes the list for CH+/CH−.
     * The same channel, already playing, is left alone. [force] rebuilds it (retry, resume after a file).
     */
    fun start(
        channel: BouquetEntry,
        channels: List<BouquetEntry>? = null,
        bouquetName: String? = null,
        bouquetReference: String? = null,
        force: Boolean = false,
    ) {
        active = true
        if (channels != null && channels.isNotEmpty()) {
            _state.update {
                it.copy(
                    channels = channels,
                    bouquetName = bouquetName ?: it.bouquetName,
                    bouquetReference = bouquetReference ?: it.bouquetReference,
                )
            }
        }
        val playing = streaming &&
            playback.health.value == StreamHealth.PLAYING &&
            !playback.isFilePlayback()
        if (StreamReuse.alreadyPlaying(_state.value.channel?.reference, channel.reference, playing, force)) {
            val current = _state.value.channel
            if (current != null && current.reference != channel.reference) {
                // Another row can share this stream. Remember it, or the next step stays here.
                EnigmaLog.service("same stream, next row: ${channel.name}")
                _state.update { it.copy(channel = channel, zapCount = it.zapCount + 1) }
            } else {
                EnigmaLog.service("same channel, stream kept: ${channel.name}")
            }
            return
        }
        restarts.reset()
        gaveUp = false
        failoverOriginId = null
        failoverTried = emptySet()
        startJob?.cancel()
        startJob = scope.launch {
            val receiver = settingsRepository.current().activeReceiver() ?: return@launch
            val running = runningRecordings
            if (running.isNotEmpty() && streamingReference() != null) {
                // Check before the current stream is closed: a refused zap keeps the picture.
                val snapshot = runCatching { tunerSource.snapshot(receiver) }.getOrNull()
                val decision = RecordingGuard.zap(snapshot, channel.reference, running, ownAddresses(receiver))
                if (decision is ZapDecision.Blocked) {
                    EnigmaLog.service("zap to ${channel.name} blocked by recording ${decision.recording.reference}")
                    if (!raiseConflict(channel)) _notices.tryEmit(blockedMessage(decision.recording))
                    return@launch
                }
            }
            beginFailover(receiver.id)
            startNow(receiver, channel)
        }
    }

    /**
     * Shows [bouquet]'s channels for zapping (list, CH+/CH−, digits) without changing the
     * channel that plays. The choice is saved, so the list opens on it next time.
     * Returns false when the channels could not be loaded.
     */
    suspend fun selectBouquet(bouquet: BouquetEntry): Boolean {
        val receiver = settingsRepository.current().activeReceiver() ?: return false
        val channels = runCatching { serviceSource.channelsInBouquet(receiver, bouquet.reference) }.getOrElse { error ->
            if (error is CancellationException) throw error
            EnigmaLog.service("bouquet ${bouquet.name} failed: ${error.javaClass.simpleName}")
            return false
        }
        _state.update { it.copy(channels = channels, bouquetName = bouquet.name, bouquetReference = bouquet.reference) }
        settingsRepository.update { settings ->
            ChannelMemory.rememberBouquet(settings, receiver.id, bouquet.reference)
        }
        EnigmaLog.service("bouquet ${bouquet.name} channels=${channels.size}")
        return true
    }

    fun zapRelative(delta: Int) {
        val current = _state.value
        val next = ChannelNavigator.step(current.channels, current.channel?.reference.orEmpty(), delta) ?: return
        start(next)
    }

    /** Returns false when the bouquet has no channel with [number]. */
    fun zapNumber(number: Int): Boolean {
        val next = ChannelNavigator.byNumber(_state.value.channels, number) ?: return false
        start(next)
        return true
    }

    fun retry() {
        val channel = _state.value.channel ?: return ensureStarted()
        start(channel, force = true)
    }

    fun numberOf(entry: BouquetEntry): Int = ChannelNavigator.numberOf(_state.value.channels, entry)

    /** The receiver is shutting down or restarting: its stream server goes away with it. */
    fun onReceiverOffline(message: UiText) {
        if (!active) return
        token += 1
        startJob?.cancel()
        playback.stopForZap()
        streaming = false
        _state.update { it.copy(tuner = TunerState(TunerStatus.PROBLEM, message, autoRetry = true)) }
    }

    /** The receiver is back (or the mode changed): pick up where the stream stopped. */
    fun onReceiverBack() {
        if (!active) return
        val status = _state.value.tuner.status
        if (status == TunerStatus.PROBLEM || status == TunerStatus.IDLE) {
            restarts.reset()
            restartAutomatically("receiver back")
        }
    }

    /** Leaving own-tuner mode: drop the stream so the other mode starts clean. */
    fun deactivate() {
        active = false
        token += 1
        restarts.reset()
        gaveUp = false
        startJob?.cancel()
        guideJob?.cancel()
        if (streaming) playback.stopForZap()
        streaming = false
        _state.update { it.copy(tuner = TunerState()) }
    }

    /**
     * The user picked another box: stop this device's stream (the old box's tuner is freed),
     * forget the tuner failover of the old box and keep the next starts on [receiverId].
     */
    fun prepareReceiverSwitch(receiverId: String) {
        deactivate()
        failoverOriginId = null
        failoverTried = emptySet()
        pinnedReceiverId = receiverId
    }

    /** Called periodically: restarts a stream that stopped because the receiver was away. */
    fun retryIfReceiverWasAway() {
        if (active && _state.value.tuner.autoRetry && startJob?.isActive != true) restartAutomatically("receiver away")
    }

    /**
     * Restarts the channel without the user asking, after a pause that grows with each try.
     * After [RestartBudget.MAX_ATTEMPTS] tries without a picture it stops and shows the error;
     * OK (or another channel) starts again.
     */
    private fun restartAutomatically(why: String) {
        if (playback.isFilePlayback()) return
        val channel = _state.value.channel ?: return
        val wait = restarts.next()
        if (wait == null) {
            giveUp()
            return
        }
        EnigmaLog.service("automatic restart ${restarts.attempts}/${RestartBudget.MAX_ATTEMPTS} in $wait ms ($why)")
        token += 1
        startJob?.cancel()
        playback.stopForZap()
        streaming = false
        _state.update {
            it.copy(
                tuner = TunerState(
                    TunerStatus.CHECKING,
                    UiText.res(R.string.tuner_retrying, restarts.attempts, RestartBudget.MAX_ATTEMPTS),
                ),
            )
        }
        failoverOriginId = null
        failoverTried = emptySet()
        startJob = scope.launch {
            delay(wait)
            val receiver = settingsRepository.current().activeReceiver() ?: return@launch
            beginFailover(receiver.id)
            startNow(receiver, channel)
        }
    }

    private fun giveUp() {
        if (gaveUp) return
        gaveUp = true
        EnigmaLog.service("stream did not start after ${RestartBudget.MAX_ATTEMPTS} automatic attempts; waiting for the user")
        _state.update {
            val reason = it.tuner.message.takeIf { message -> it.tuner.status == TunerStatus.PROBLEM && message.isNotEmpty() }
            val text = UiText.res(R.string.tuner_gave_up, RestartBudget.MAX_ATTEMPTS)
            it.copy(tuner = TunerState(TunerStatus.PROBLEM, if (reason == null) text else UiText.Joined(listOf(reason, text)), autoRetry = false))
        }
    }

    /** Reloads now/next when the shown programme has ended. */
    fun refreshGuideIfStale() {
        val state = _state.value
        val channel = state.channel ?: return
        val current = state.guide?.currentEvent
        val nowSec = System.currentTimeMillis() / 1000
        val stale = state.guide == null || current == null ||
            (current.beginEpochSec > 0 && current.beginEpochSec + current.durationSec <= nowSec)
        if (stale && guideJob?.isActive != true) loadGuide(channel, token)
    }

    private suspend fun startNow(receiver: ReceiverConfig, channel: BouquetEntry) {
        val myToken = ++token
        val hadStream = streaming || playback.hasOpenStream()
        guideJob?.cancel()
        _state.update {
            it.copy(
                channel = channel,
                guide = it.guide?.takeIf { guide -> BouquetChannels.sameService(guide.serviceReference, channel.reference) },
                tuner = TunerState(TunerStatus.CHECKING, UiText.res(R.string.tuner_checking)),
                zapCount = it.zapCount + 1,
            )
        }
        val ticket = playback.stopForZap()
        streaming = false
        loadGuide(channel, myToken)
        val internet = ServiceStreams.isInternet(channel.reference)
        if (internet) {
            // IPTV / internet: play the URL in the service reference; no tuner, no Enigma2 stream port.
            EnigmaLog.service("internet stream ${channel.name}: skipping tuner check")
            _state.update { it.copy(tuner = TunerState(TunerStatus.STARTING, UiText.res(R.string.tuner_starting))) }
            val target = streamTargets.target(receiver, channel.reference)
            if (myToken != token || playback.streamHoldTicket() != ticket) return
            playback.playAfterRelease(ticket, target.url, force = true, authorization = target.authorization, checkStart = false, internet = true)
            streaming = true
            saveLastChannel(receiver.id, channel)
            return
        }
        // Give the receiver a moment to notice the closed stream and free its tuner.
        // Retries stay blocked until playAfterRelease, so the old channel cannot grab the tuner again.
        if (hadStream) delay(STREAM_RELEASE_MS)
        val snapshot = runCatching { tunerSource.snapshot(receiver) }.getOrElse { error ->
            if (error is CancellationException) throw error
            EnigmaLog.service("tuner check unavailable: ${error.javaClass.simpleName}")
            null
        }
        if (myToken != token || playback.streamHoldTicket() != ticket) return
        lastSnapshot = snapshot
        lastOwnAddresses = ownAddresses(receiver)
        if (myToken != token || playback.streamHoldTicket() != ticket) return
        val verdict = snapshot?.let {
            TunerAvailability.evaluate(it, channel.reference, lastOwnAddresses)
        } ?: TunerVerdict.Unknown
        EnigmaLog.service("tuner check ${channel.name}: $verdict")
        if (verdict is TunerVerdict.Full) {
            if (tryNextBox(channel)) return
            restoreOrigin()
            EnigmaLog.service("tuners full for ${channel.name}")
            freshRunning()
            val recording = runningRecordings.firstOrNull { !RecordingGuard.sameTransponder(it.reference, channel.reference) }
            val message = recording?.let(::blockedMessage) ?: fullMessage(verdict.used, verdict.total)
            raiseConflict(channel)
            _state.update {
                it.copy(tuner = TunerState(TunerStatus.FULL, message, verdict.used, verdict.total, verdict.users))
            }
            return
        }
        _state.update { it.copy(tuner = TunerState(TunerStatus.STARTING, UiText.res(R.string.tuner_starting))) }
        val target = streamTargets.target(receiver, channel.reference)
        if (myToken != token) return
        playback.playAfterRelease(ticket, target.url, force = true, authorization = target.authorization, checkStart = true)
        streaming = true
        saveLastChannel(receiver.id, channel)
    }

    private fun loadGuide(channel: BouquetEntry, myToken: Int) {
        guideJob?.cancel()
        guideJob = scope.launch {
            val receiver = settingsRepository.current().activeReceiver() ?: return@launch
            runCatching { serviceSource.channelGuide(receiver, channel.reference, channel.name) }
                .onSuccess { guide ->
                    if (myToken != token) return@onSuccess
                    _state.update { it.copy(guide = guide) }
                }
        }
    }

    private fun onHealth(health: StreamHealth) {
        if (!active || !streaming || playback.isFilePlayback()) return
        val tuner = when (health) {
            StreamHealth.IDLE -> return
            StreamHealth.STARTING -> TunerState(TunerStatus.STARTING, UiText.res(R.string.tuner_starting))
            StreamHealth.PLAYING -> {
                pinnedReceiverId = null
                restarts.reset()
                gaveUp = false
                TunerState(TunerStatus.PLAYING)
            }
            StreamHealth.NO_TUNER -> {
                val channel = _state.value.channel
                if (channel != null && !ServiceStreams.isInternet(channel.reference)) {
                    streaming = false
                    startJob?.cancel()
                    startJob = scope.launch {
                        if (tryNextBox(channel)) return@launch
                        restoreOrigin()
                        if (conflictAfterFailure(channel, health)) return@launch
                        val refused = TunerAvailability.refused(lastSnapshot, channel.reference, lastOwnAddresses)
                        _state.update {
                            it.copy(tuner = TunerState(TunerStatus.FULL, fullMessage(refused.used, refused.total), refused.used, refused.total, refused.users))
                        }
                    }
                    return
                }
                val refused = TunerAvailability.refused(lastSnapshot, _state.value.channel?.reference.orEmpty(), lastOwnAddresses)
                TunerState(TunerStatus.FULL, fullMessage(refused.used, refused.total), refused.used, refused.total, refused.users)
            }
            StreamHealth.AUTH_REQUIRED -> TunerState(
                TunerStatus.PROBLEM,
                UiText.res(R.string.tuner_auth_required),
            )
            StreamHealth.UNREACHABLE -> TunerState(
                TunerStatus.PROBLEM,
                UiText.res(R.string.tuner_stream_port_unreachable),
                autoRetry = true,
            )
            StreamHealth.NO_PICTURE -> TunerState(
                TunerStatus.NO_PICTURE,
                UiText.res(R.string.tuner_no_picture_detail),
            )
            StreamHealth.FAILED -> TunerState(TunerStatus.PROBLEM, UiText.res(R.string.tuner_stream_interrupted))
            // Internet / IPTV: the player already used its three tries; OK starts again.
            StreamHealth.INTERNET_UNSUPPORTED -> TunerState(TunerStatus.PROBLEM, UiText.res(R.string.internet_stream_unsupported))
            StreamHealth.INTERNET_FAILED -> TunerState(
                TunerStatus.PROBLEM,
                UiText.res(R.string.internet_stream_failed, RestartBudget.MAX_ATTEMPTS),
            )
        }
        if (health == StreamHealth.NO_TUNER || health == StreamHealth.AUTH_REQUIRED || health == StreamHealth.UNREACHABLE ||
            health == StreamHealth.INTERNET_UNSUPPORTED || health == StreamHealth.INTERNET_FAILED
        ) {
            streaming = false
        }
        if (health == StreamHealth.NO_PICTURE || health == StreamHealth.FAILED) {
            _state.value.channel?.takeIf { !ServiceStreams.isInternet(it.reference) }?.let { channel ->
                scope.launch { conflictAfterFailure(channel, health) }
            }
        }
        if (tuner.autoRetry && restarts.exhausted) {
            // The automatic tries are used up: say so now and wait for the user.
            gaveUp = true
            EnigmaLog.service("stream did not start after ${RestartBudget.MAX_ATTEMPTS} automatic attempts; waiting for the user")
            val text = UiText.res(R.string.tuner_gave_up, RestartBudget.MAX_ATTEMPTS)
            _state.update { it.copy(tuner = tuner.copy(message = UiText.Joined(listOf(tuner.message, text)), autoRetry = false)) }
            return
        }
        _state.update { it.copy(tuner = tuner) }
    }

    private fun beginFailover(receiverId: String) {
        failoverOriginId = receiverId
        failoverTried = setOf(receiverId)
    }

    /**
     * The active box has no free tuner. Try the next saved box, in list order after the one
     * the user started on, wrapping around. Returns false when every box has been tried.
     */
    private suspend fun tryNextBox(channel: BouquetEntry): Boolean {
        // Free accounts use one receiver only: no failover to another box.
        if (!premiumGate.allows(com.enigmacast.domain.account.PremiumFeature.MULTIPLE_RECEIVERS)) return false
        val settings = settingsRepository.current()
        if (pinnedReceiverId != null && pinnedReceiverId == settings.activeReceiver()?.id) {
            EnigmaLog.service("tuners full on the chosen box; staying on it")
            return false
        }
        val next = ReceiverList.nextAfter(settings.receivers, failoverOriginId, failoverTried) ?: return false
        failoverTried = failoverTried + next.id
        EnigmaLog.service("tuners full, trying ${next.name}")
        boundReceiverId = next.id
        settingsRepository.selectReceiver(next.id)
        _state.update { it.copy(tuner = TunerState(TunerStatus.CHECKING, UiText.res(R.string.tuner_checking))) }
        startNow(next, channel)
        return true
    }

    /** Every box was full: the box the user had selected stays the active one. */
    private suspend fun restoreOrigin() {
        val originId = failoverOriginId ?: return
        val settings = settingsRepository.current()
        if (settings.activeReceiver()?.id == originId) return
        val origin = settings.receivers.firstOrNull { it.id == originId } ?: return
        boundReceiverId = origin.id
        settingsRepository.selectReceiver(origin.id)
    }

    private fun saveLastChannel(receiverId: String, channel: BouquetEntry) {
        val bouquet = _state.value.bouquetReference
        scope.launch {
            settingsRepository.update { settings ->
                val last = LastChannel(channel.reference, channel.name, bouquet)
                ChannelMemory.remember(settings, receiverId, last)
            }
        }
    }

    /** The addresses the receiver sees for this device, so its own streams are not counted as other viewers. */
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

    companion object {
        private const val STREAM_RELEASE_MS = 400L

        fun fullMessage(used: Int, total: Int): UiText {
            return if (total > 0) UiText.res(R.string.tuner_full_count, used, total) else UiText.res(R.string.tuner_full)
        }
    }
}
