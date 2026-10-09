package com.enigmacast.live

import com.enigmacast.R
import com.enigmacast.data.openwebif.OpenWebifEndpoints
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ChannelGuide
import com.enigmacast.domain.model.ChannelMemory
import com.enigmacast.domain.model.LastChannel
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.service.BouquetChannels
import com.enigmacast.domain.service.ServiceStreams
import com.enigmacast.domain.source.ServiceStateSource
import com.enigmacast.domain.source.TunerStatusSource
import com.enigmacast.domain.tuner.ChannelNavigator
import com.enigmacast.domain.tuner.RestartBudget
import com.enigmacast.domain.tuner.StreamHealth
import com.enigmacast.domain.tuner.TunerAvailability
import com.enigmacast.domain.tuner.TunerSnapshot
import com.enigmacast.domain.tuner.TunerUser
import com.enigmacast.domain.tuner.TunerVerdict
import com.enigmacast.playback.LivePlaybackController
import com.enigmacast.util.EnigmaLog
import com.enigmacast.util.UiText
import com.enigmacast.util.isNotEmpty
import com.enigmacast.util.uiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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
            val id = settingsRepository.current().activeReceiver()?.id
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
            if (current.channel != null) {
                if (current.tuner.status == TunerStatus.IDLE) start(current.channel)
                return@launch
            }
            if (current.channels.isEmpty()) loadInitial(startPlayback = true) else current.channels.firstOrNull()?.let(::start)
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
        if (channel != null) start(channel) else ensureStarted()
    }

    private fun loadInitial(startPlayback: Boolean) {
        startAfterLoad = startPlayback
        startJob = scope.launch {
            _state.update {
                it.copy(
                    loadingChannels = true,
                    tuner = TunerState(TunerStatus.CHECKING, UiText.res(R.string.tuner_loading_channels)),
                )
            }
            try {
                val settings = settingsRepository.current()
                val receiver = settings.activeReceiver() ?: return@launch
                boundReceiverId = receiver.id
                val last = ChannelMemory.lastFor(settings, receiver.id)
                val list = runCatching {
                    if (last.bouquetReference.isNotBlank()) {
                        val channels = serviceSource.channelsInBouquet(receiver, last.bouquetReference)
                        val name = runCatching {
                            serviceSource.televisionBouquets(receiver) + serviceSource.radioBouquets(receiver)
                        }.getOrDefault(emptyList()).firstOrNull { it.reference == last.bouquetReference }?.name.orEmpty()
                        Triple(name, last.bouquetReference, channels)
                    } else {
                        val current = serviceSource.currentBouquetChannels(receiver)
                        Triple(current.bouquetName, current.bouquetReference, current.channels)
                    }
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    _state.update { it.copy(tuner = TunerState(TunerStatus.PROBLEM, error.uiMessage(UiText.res(R.string.tuner_channels_failed)))) }
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
                    ?: last.reference.takeIf { it.isNotBlank() }?.let { BouquetEntry(last.name, it) }
                    ?: channels.firstOrNull()
                _state.update {
                    it.copy(
                        channels = channels,
                        bouquetName = bouquetName,
                        bouquetReference = bouquetReference,
                        loadingChannels = false,
                    )
                }
                if (!startAfterLoad || playback.isFilePlayback()) return@launch
                if (target == null) {
                    _state.update { it.copy(tuner = TunerState(TunerStatus.PROBLEM, UiText.res(R.string.tuner_no_channels))) }
                    return@launch
                }
                startNow(receiver, target)
            } finally {
                if (_state.value.loadingChannels) _state.update { it.copy(loadingChannels = false) }
            }
        }
    }

    /** Zap this device's stream to [channel]; [channels] becomes the list for CH+/CH−. */
    fun start(
        channel: BouquetEntry,
        channels: List<BouquetEntry>? = null,
        bouquetName: String? = null,
        bouquetReference: String? = null,
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
        restarts.reset()
        gaveUp = false
        startJob?.cancel()
        startJob = scope.launch {
            val receiver = settingsRepository.current().activeReceiver() ?: return@launch
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
        start(channel)
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
        startJob = scope.launch {
            delay(wait)
            val receiver = settingsRepository.current().activeReceiver() ?: return@launch
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
            _state.update {
                it.copy(tuner = TunerState(TunerStatus.FULL, fullMessage(verdict.used, verdict.total), verdict.used, verdict.total, verdict.users))
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
                restarts.reset()
                gaveUp = false
                TunerState(TunerStatus.PLAYING)
            }
            StreamHealth.NO_TUNER -> {
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
