package com.enigmacast.live

import com.enigmacast.data.openwebif.OpenWebifEndpoints
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ChannelGuide
import com.enigmacast.domain.model.LastChannel
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.service.BouquetChannels
import com.enigmacast.domain.source.ServiceStateSource
import com.enigmacast.domain.source.TunerStatusSource
import com.enigmacast.domain.tuner.ChannelNavigator
import com.enigmacast.domain.tuner.StreamHealth
import com.enigmacast.domain.tuner.TunerAvailability
import com.enigmacast.domain.tuner.TunerSnapshot
import com.enigmacast.domain.tuner.TunerUser
import com.enigmacast.domain.tuner.TunerVerdict
import com.enigmacast.playback.LivePlaybackController
import com.enigmacast.util.EnigmaLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val message: String = "",
    val used: Int = 0,
    val total: Int = 0,
    val users: List<TunerUser> = emptyList(),
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(OwnTunerState())
    val state: StateFlow<OwnTunerState> = _state.asStateFlow()
    private var startJob: Job? = null
    private var guideJob: Job? = null
    private var token = 0
    private var lastSnapshot: TunerSnapshot? = null
    private var lastOwnAddresses: Set<String> = emptySet()
    private var streaming = false
    private var active = false

    init {
        scope.launch {
            playback.health.collect(::onHealth)
        }
    }

    /** Starts the last watched channel, or the receiver's last channel, once per app run. */
    fun ensureStarted() {
        active = true
        val current = _state.value
        if (current.channel != null) {
            if (current.tuner.status == TunerStatus.IDLE) start(current.channel)
            return
        }
        if (startJob?.isActive == true) return
        startJob = scope.launch {
            val settings = settingsRepository.current()
            val receiver = settings.activeReceiver() ?: return@launch
            val last = settings.lastChannel
            _state.update { it.copy(tuner = TunerState(TunerStatus.CHECKING, "Zenders laden…")) }
            val list = runCatching {
                if (last.bouquetReference.isNotBlank()) {
                    val channels = serviceSource.channelsInBouquet(receiver, last.bouquetReference)
                    val name = runCatching { serviceSource.televisionBouquets(receiver) }.getOrDefault(emptyList())
                        .firstOrNull { it.reference == last.bouquetReference }?.name.orEmpty()
                    Triple(name, last.bouquetReference, channels)
                } else {
                    val current = serviceSource.currentBouquetChannels(receiver)
                    Triple(current.bouquetName, current.bouquetReference, current.channels)
                }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                _state.update { it.copy(tuner = TunerState(TunerStatus.PROBLEM, error.message ?: "Zenders laden mislukt")) }
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
            _state.update { it.copy(channels = channels, bouquetName = bouquetName, bouquetReference = bouquetReference) }
            if (target == null) {
                _state.update { it.copy(tuner = TunerState(TunerStatus.PROBLEM, "Geen zenders gevonden")) }
                return@launch
            }
            startNow(receiver, target)
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
        startJob?.cancel()
        startJob = scope.launch {
            val receiver = settingsRepository.current().activeReceiver() ?: return@launch
            startNow(receiver, channel)
        }
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
    fun onReceiverOffline(message: String) {
        if (!active) return
        token += 1
        startJob?.cancel()
        playback.stopForZap()
        streaming = false
        _state.update { it.copy(tuner = TunerState(TunerStatus.PROBLEM, message)) }
    }

    /** The receiver is back (or the mode changed): pick up where the stream stopped. */
    fun onReceiverBack() {
        if (!active) return
        val status = _state.value.tuner.status
        if (status == TunerStatus.PROBLEM || status == TunerStatus.IDLE) retry()
    }

    /** Leaving own-tuner mode: drop the stream so the other mode starts clean. */
    fun deactivate() {
        active = false
        token += 1
        startJob?.cancel()
        guideJob?.cancel()
        if (streaming) playback.stopForZap()
        streaming = false
        _state.update { it.copy(tuner = TunerState()) }
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
        val hadStream = streaming
        guideJob?.cancel()
        _state.update {
            it.copy(
                channel = channel,
                guide = it.guide?.takeIf { guide -> BouquetChannels.sameService(guide.serviceReference, channel.reference) },
                tuner = TunerState(TunerStatus.CHECKING, "Tuner controleren…"),
                zapCount = it.zapCount + 1,
            )
        }
        playback.stopForZap()
        streaming = false
        loadGuide(channel, myToken)
        // Give the receiver a moment to notice the closed stream and free its tuner.
        if (hadStream) delay(STREAM_RELEASE_MS)
        val snapshot = runCatching { tunerSource.snapshot(receiver) }.getOrElse { error ->
            if (error is CancellationException) throw error
            EnigmaLog.service("tuner check unavailable: ${error.javaClass.simpleName}")
            null
        }
        if (myToken != token) return
        lastSnapshot = snapshot
        lastOwnAddresses = ownAddresses(receiver)
        if (myToken != token) return
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
        _state.update { it.copy(tuner = TunerState(TunerStatus.STARTING, "Stream starten…")) }
        playback.play(
            endpoints.stream(receiver, channel.reference),
            force = true,
            authorization = endpoints.streamAuthorization(receiver),
            checkStart = true,
        )
        streaming = true
        saveLastChannel(channel)
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
        if (!active || !streaming) return
        val tuner = when (health) {
            StreamHealth.IDLE -> return
            StreamHealth.STARTING -> TunerState(TunerStatus.STARTING, "Stream starten…")
            StreamHealth.PLAYING -> TunerState(TunerStatus.PLAYING)
            StreamHealth.NO_TUNER -> {
                val refused = TunerAvailability.refused(lastSnapshot, _state.value.channel?.reference.orEmpty(), lastOwnAddresses)
                TunerState(TunerStatus.FULL, fullMessage(refused.used, refused.total), refused.used, refused.total, refused.users)
            }
            StreamHealth.AUTH_REQUIRED -> TunerState(
                TunerStatus.PROBLEM,
                "De ontvanger vraagt een wachtwoord voor streams. Vul de gebruikersnaam en het wachtwoord van de ontvanger in bij Ontvangers.",
            )
            StreamHealth.UNREACHABLE -> TunerState(
                TunerStatus.PROBLEM,
                "Streampoort niet bereikbaar. Staat de ontvanger uit of in diepe stand-by, of klopt de streampoort niet?",
            )
            StreamHealth.NO_PICTURE -> TunerState(
                TunerStatus.NO_PICTURE,
                "Wel signaal, geen beeld. De zender is mogelijk gecodeerd en de CI-module of smartcard kan hem nu niet vrijgeven.",
            )
            StreamHealth.FAILED -> TunerState(TunerStatus.PROBLEM, "Stream onderbroken, opnieuw verbinden…")
        }
        if (health == StreamHealth.NO_TUNER || health == StreamHealth.AUTH_REQUIRED || health == StreamHealth.UNREACHABLE) {
            streaming = false
        }
        _state.update { it.copy(tuner = tuner) }
    }

    private fun saveLastChannel(channel: BouquetEntry) {
        val bouquet = _state.value.bouquetReference
        scope.launch {
            settingsRepository.update { settings ->
                settings.copy(lastChannel = LastChannel(channel.reference, channel.name, bouquet))
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

        fun fullMessage(used: Int, total: Int): String {
            val count = if (total > 0) " ($used/$total)" else ""
            return "Alle tuners zijn bezet$count. Probeer het later opnieuw of kies een zender op dezelfde transponder."
        }
    }
}
