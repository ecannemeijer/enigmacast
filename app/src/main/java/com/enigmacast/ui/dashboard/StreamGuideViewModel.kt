package com.enigmacast.ui.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.R
import com.enigmacast.data.openwebif.OpenWebifEndpoints
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.epg.ProgrammePrompt
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ChannelGuide
import com.enigmacast.domain.model.PowerAction
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.model.StreamMode
import com.enigmacast.domain.power.ReceiverPowerFlow
import com.enigmacast.domain.service.BouquetChannels
import com.enigmacast.domain.service.LiveZapFlow
import com.enigmacast.domain.source.ReceiverInfoSource
import com.enigmacast.domain.source.ServiceStateSource
import com.enigmacast.domain.tuner.ChannelNavigator
import com.enigmacast.domain.tuner.StreamModePolicy
import com.enigmacast.live.GuideGridController
import com.enigmacast.live.GuideRepository
import com.enigmacast.live.OwnTunerSession
import com.enigmacast.live.OwnTunerState
import com.enigmacast.live.PiconRepository
import com.enigmacast.live.ReminderScheduler
import com.enigmacast.live.StreamQualityController
import com.enigmacast.live.StreamTargets
import com.enigmacast.live.TunerStatus
import com.enigmacast.live.text
import com.enigmacast.playback.LivePlaybackController
import com.enigmacast.ui.remote.AutoSwitchTexts
import com.enigmacast.ui.remote.internetProblem
import com.enigmacast.util.EnigmaLog
import com.enigmacast.util.UiText
import com.enigmacast.util.isEmpty
import com.enigmacast.util.uiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StreamGuideViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val serviceSource: ServiceStateSource,
    private val infoSource: ReceiverInfoSource,
    private val playback: LivePlaybackController,
    private val endpoints: OpenWebifEndpoints,
    private val session: OwnTunerSession,
    private val streamTargets: StreamTargets,
    guideRepository: GuideRepository,
    private val picons: PiconRepository,
    private val quality: StreamQualityController,
    private val reminders: ReminderScheduler,
    private val recordings: com.enigmacast.live.RecordingBook,
) : ViewModel() {
    var guide by mutableStateOf<ChannelGuide?>(null)
        private set
    var channels by mutableStateOf<List<BouquetEntry>>(emptyList())
        private set
    var bouquetName by mutableStateOf("")
        private set
    var bouquets by mutableStateOf<List<BouquetEntry>>(emptyList())
        private set
    var selectedBouquetReference by mutableStateOf("")
        private set
    var tunedReference by mutableStateOf("")
        private set
    var displayName by mutableStateOf("")
        private set
    var status by mutableStateOf(UiText.Empty)
        private set
    var askWake by mutableStateOf(false)
        private set
    var waking by mutableStateOf(false)
        private set
    var programmePrompt by mutableStateOf<ProgrammePrompt?>(null)
        private set
    var recordAsk by mutableStateOf<com.enigmacast.domain.recording.RecordAsk?>(null)
        private set

    /** Programmes with AutoSwitch set ([GuideEvent.programmeKey]), marked in the grid. */
    val autoSwitchKeys: StateFlow<Set<String>> = reminders.scheduledKeys

    /** A short message under the mini player (AutoSwitch set, recording coming soon, ...). */
    var notice by mutableStateOf(UiText.Empty)
        private set
    /** Service references of channels this app is recording now. */
    var recordingReferences by mutableStateOf<Set<String>>(emptySet())
        private set
    private var noticeJob: Job? = null

    /** The programme grid of the Stream page: this bouquet's channels on a time axis. */
    val grid = GuideGridController(viewModelScope, guideRepository, { settingsRepository.current().activeReceiver() })
    private var tuneJob: Job? = null
    private var lastPressedReference = ""
    private var previewJob: Job? = null
    private var shownReference = ""
    private var guideToken = 0
    private var bouquetLoaded = false
    private var bouquetPinned = false
    private var bouquetJob: Job? = null
    private var bouquetToken = 0
    private var powerCheckStarted = false
    private var powerKnown = false
    private var leaveOff = false
    private var mode: StreamMode? = null
    private var standbyJob: Job? = null

    /** True while the guide drives this device's own stream instead of the receiver. */
    val ownTuner: Boolean get() = mode == StreamMode.OWN_TUNER

    init {
        viewModelScope.launch {
            settingsRepository.settings.map { it.streamMode }.distinctUntilChanged().collect(::onModeChanged)
        }
        viewModelScope.launch {
            session.state.collect(::mirror)
        }
        viewModelScope.launch {
            quality.notices.collect { showNotice(it, NOTICE_LONG_MS) }
        }
        viewModelScope.launch {
            playback.health.collect { health ->
                if (mode != StreamMode.FOLLOW_RECEIVER) return@collect
                internetProblem(health)?.let { showNotice(it, NOTICE_LONG_MS) }
            }
        }
        viewModelScope.launch {
            snapshotFlow { Triple(selectedBouquetReference, channels, tunedReference) }.collect { (bouquet, list, tuned) ->
                grid.show(bouquet, list, tuned)
            }
        }
        viewModelScope.launch {
            recordings.items.collect { list ->
                recordingReferences = list
                    .filter { it.status == com.enigmacast.domain.recording.RecordingStatus.RECORDING }
                    .map { it.reference }
                    .toSet()
            }
        }
    }

    /** Up/Down/CH± in the grid. A new row forgets the OK press, so OK there tunes instead of going full screen. */
    fun moveGridRows(delta: Int) {
        grid.moveRows(delta)
        val focused = grid.state.value.focusedChannel
        if (focused == null || !BouquetChannels.sameService(focused.reference, lastPressedReference)) {
            lastPressedReference = ""
        }
    }

    /** Left/Right in the grid; false when Left is already on the programme on air (the bouquets open then). */
    fun moveGridTime(direction: Int): Boolean = grid.moveTime(direction)

    /** Back from the player: the highlight on the channel that is playing, at the current time. */
    fun focusTunedInGrid() {
        if (tunedReference.isNotBlank()) grid.focusChannel(tunedReference)
    }

    /**
     * OK in the grid: a programme on air (or a gap now) starts that channel in the mini player,
     * a second OK on it goes full screen; a later programme offers AutoSwitch or Record.
     */
    fun chooseInGrid(onStarted: () -> Unit, onFullscreen: () -> Unit) {
        val (channel, cell) = grid.focused() ?: return
        val event = cell.event
        val now = System.currentTimeMillis() / 1000
        if (cell.startSec <= now) {
            tune(channel, onStarted, onFullscreen)
            return
        }
        if (event == null) return
        programmePrompt = ProgrammePrompt(event, channel.name, reminders.isScheduled(event.programmeKey))
    }

    /** AutoSwitch in the programme dialog: set it, or cancel it when it was set. */
    fun toggleAutoSwitch() {
        val prompt = programmePrompt ?: return
        programmePrompt = null
        viewModelScope.launch {
            val receiverId = settingsRepository.current().activeReceiver()?.id.orEmpty()
            showNotice(AutoSwitchTexts.result(reminders.toggle(prompt.event, prompt.channelName, receiverId), prompt), NOTICE_LONG_MS)
        }
    }

    /** Record in the programme dialog: confirm the destination, then store it. */
    fun chooseRecord() {
        val prompt = programmePrompt ?: return
        programmePrompt = null
        viewModelScope.launch {
            when (val result = recordings.prepare(prompt.event, prompt.channelName)) {
                is com.enigmacast.live.RecordingBook.PrepareResult.Ask -> recordAsk = result.ask
                is com.enigmacast.live.RecordingBook.PrepareResult.Message -> showNotice(result.text, NOTICE_LONG_MS)
            }
        }
    }

    fun confirmRecord() {
        val ask = recordAsk ?: return
        recordAsk = null
        viewModelScope.launch { showNotice(recordings.schedule(ask), NOTICE_LONG_MS) }
    }

    fun dismissRecord() {
        recordAsk = null
    }

    fun dismissProgramme() {
        programmePrompt = null
    }

    private fun showNotice(text: UiText, durationMs: Long = NOTICE_MS) {
        notice = text
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(durationMs)
            notice = UiText.Empty
        }
    }

    suspend fun picon(entry: BouquetEntry): ImageBitmap? {
        val receiver = settingsRepository.current().activeReceiver() ?: return null
        return picons.load(receiver, entry)
    }

    fun cachedPicon(entry: BouquetEntry): ImageBitmap? = picons.cached(entry)

    fun numberOf(entry: BouquetEntry): Int = ChannelNavigator.numberOf(channels, entry)

    /**
     * Switching modes drops the running stream: the follow mode streams what the receiver
     * shows, the own-tuner mode streams its own channel, and they must not mix.
     * Own tuner also puts the receiver in standby, including when the app opens already in that mode.
     */
    private fun onModeChanged(next: StreamMode) {
        val previous = mode
        mode = next
        if (StreamModePolicy.putsReceiverInStandby(next) && previous != next) {
            standbyForOwnTuner()
        } else if (!StreamModePolicy.putsReceiverInStandby(next)) {
            standbyJob?.cancel()
        }
        if (previous == null || previous == next) return
        EnigmaLog.service("stream mode $previous → $next")
        tuneJob?.cancel()
        previewJob?.cancel()
        guideToken += 1
        if (previous == StreamMode.OWN_TUNER) session.deactivate() else playback.stopForZap()
        powerCheckStarted = false
        powerKnown = false
        leaveOff = false
        askWake = false
        lastPressedReference = ""
        status = UiText.Empty
        refresh()
    }

    private fun mirror(state: OwnTunerState) {
        if (mode != StreamMode.OWN_TUNER) return
        if (!bouquetPinned && state.channels.isNotEmpty()) {
            channels = state.channels
            bouquetName = state.bouquetName
            selectedBouquetReference = state.bouquetReference
            bouquetLoaded = true
        }
        val channel = state.channel
        if (channel != null) {
            val browsing = browsingAwayFromTuned()
            tunedReference = channel.reference
            if (!browsing) {
                shownReference = channel.reference
                displayName = channel.name
                guide = state.guide?.takeIf { BouquetChannels.sameService(it.serviceReference, channel.reference) }
            }
        }
        status = when (state.tuner.status) {
            TunerStatus.PLAYING, TunerStatus.IDLE -> UiText.Empty
            TunerStatus.FULL -> UiText.Joined(
                listOf(state.tuner.message) + state.tuner.users.map { UiText.res(R.string.bullet_item, it.text()) },
            )
            else -> state.tuner.message
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val current = mode ?: settingsRepository.current().streamMode.also(::onModeChanged)
            if (current == StreamMode.OWN_TUNER) refreshOwnTuner() else refreshFollowing()
        }
    }

    /**
     * Own tuner leaves the receiver's screen off. Skip the command when it is already
     * in standby, so opening the app does not toggle it back on.
     */
    private fun standbyForOwnTuner() {
        standbyJob?.cancel()
        standbyJob = viewModelScope.launch {
            val receiver = settingsRepository.current().activeReceiver() ?: return@launch
            val already = try {
                infoSource.powerState(receiver).inStandby
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (already == true) return@launch
            ReceiverPowerFlow.run(
                action = PowerAction.STANDBY,
                wasInStandby = already,
                send = { infoSource.setPowerState(receiver, PowerAction.STANDBY) },
                readStandby = { infoSource.powerState(receiver).inStandby },
            )
        }
    }

    /** Own tuner: never wake or zap the receiver; standby is requested separately. */
    private suspend fun refreshOwnTuner() {
        session.ensureStarted()
        session.retryIfReceiverWasAway()
        session.refreshGuideIfStale()
        if (bouquets.isEmpty()) {
            val receiver = settingsRepository.current().activeReceiver() ?: return
            runCatching { serviceSource.televisionBouquets(receiver) }.onSuccess { bouquets = it }
        }
        if (browsingAwayFromTuned()) reloadShownGuide()
    }

    private fun refreshFollowing() {
        val shouldCheckPower = !powerCheckStarted
        if (shouldCheckPower) powerCheckStarted = true
        viewModelScope.launch {
            if (shouldCheckPower) {
                val receiver = settingsRepository.current().activeReceiver()
                val standby = receiver?.let { box ->
                    runCatching { infoSource.powerState(box).inStandby }.getOrNull()
                }
                if (standby == true) {
                    askWake = true
                    status = UiText.res(R.string.guide_receiver_off)
                    return@launch
                }
                powerKnown = true
            }
            if (askWake || leaveOff || waking || !powerKnown) return@launch
            if (browsingAwayFromTuned()) {
                reloadShownGuide()
            } else {
                loadGuide()
            }
            if (!bouquetLoaded || channels.isEmpty()) loadBouquet()
        }
    }

    fun confirmWake() {
        if (waking) return
        viewModelScope.launch {
            val receiver = settingsRepository.current().activeReceiver()
            if (receiver == null) {
                askWake = false
                return@launch
            }
            waking = true
            val outcome = ReceiverPowerFlow.run(
                action = PowerAction.WAKE_UP,
                wasInStandby = true,
                send = { infoSource.wake(receiver) },
                readStandby = { infoSource.powerState(receiver).inStandby },
            )
            waking = false
            if (outcome is ReceiverPowerFlow.Outcome.Failed) {
                status = UiText.res(R.string.guide_wake_failed)
                return@launch
            }
            if (outcome != ReceiverPowerFlow.Outcome.Confirmed(inStandby = false)) {
                status = UiText.res(R.string.guide_receiver_not_responding)
                return@launch
            }
            askWake = false
            leaveOff = false
            powerKnown = true
            status = UiText.Empty
            loadGuide()
            if (!bouquetLoaded || channels.isEmpty()) loadBouquet()
        }
    }

    fun declineWake() {
        if (waking) return
        askWake = false
        leaveOff = true
        powerKnown = true
        status = UiText.res(R.string.guide_receiver_off)
    }

    /**
     * The receiver went to standby, or is restarting or shutting down, after a command from
     * the Receiver section. Free the stream so the tuner can sleep and stop loading the guide.
     */
    fun onReceiverOff(message: UiText) {
        if (!StreamModePolicy.stopsStreamOnStandby(mode ?: StreamMode.OWN_TUNER)) return
        if (askWake || waking) return
        tuneJob?.cancel()
        previewJob?.cancel()
        guideToken += 1
        playback.stopForZap()
        leaveOff = true
        powerKnown = true
        status = message
    }

    /**
     * The receiver is shutting down or restarting. In own-tuner mode its stream server
     * goes away too, so the stream stops either way.
     */
    fun onReceiverGoingOffline(message: UiText) {
        if (mode == StreamMode.FOLLOW_RECEIVER) onReceiverOff(message) else session.onReceiverOffline(message)
    }

    /** The receiver is on again; resume only if the guide had stopped because it was off. */
    fun onReceiverOn() {
        if (mode != StreamMode.FOLLOW_RECEIVER) {
            session.onReceiverBack()
            return
        }
        if (!leaveOff || askWake || waking) return
        leaveOff = false
        powerKnown = true
        status = UiText.Empty
        viewModelScope.launch {
            loadGuide()
            if (!bouquetLoaded || channels.isEmpty()) loadBouquet()
        }
    }

    fun refreshIfServiceChanged() {
        if (mode != StreamMode.FOLLOW_RECEIVER) return
        if (!powerKnown || askWake || leaveOff || waking) return
        if (browsingAwayFromTuned() || previewJob?.isActive == true || tuneJob?.isActive == true || bouquetJob?.isActive == true) return
        viewModelScope.launch {
            val receiver = settingsRepository.current().activeReceiver() ?: return@launch
            val reference = runCatching { serviceSource.currentService(receiver).reference }.getOrNull()
            if (reference.isNullOrBlank()) return@launch
            if (!BouquetChannels.sameService(reference, guide?.serviceReference.orEmpty())) {
                loadGuide()
            }
            val known = channels.any { BouquetChannels.sameService(it.reference, reference) }
            if (bouquetLoaded && !known && !bouquetPinned) loadBouquet()
        }
    }

    fun tune(channel: BouquetEntry, onStarted: () -> Unit, onFullscreen: () -> Unit) {
        if (lastPressedReference.isNotBlank() && BouquetChannels.sameService(channel.reference, lastPressedReference)) {
            val refused = session.state.value.tuner.status.let { it == TunerStatus.FULL || it == TunerStatus.PROBLEM }
            if (mode != StreamMode.FOLLOW_RECEIVER && refused) session.retry() else onFullscreen()
            return
        }
        lastPressedReference = channel.reference
        if (mode != StreamMode.FOLLOW_RECEIVER) {
            previewJob?.cancel()
            guideToken += 1
            bouquetPinned = false
            tunedReference = channel.reference
            shownReference = channel.reference
            displayName = channel.name
            session.start(channel, channels, bouquetName, selectedBouquetReference)
            onStarted()
            return
        }
        val token = ++guideToken
        tuneJob?.cancel()
        previewJob?.cancel()
        val previous = tunedReference
        tunedReference = channel.reference
        tuneJob = viewModelScope.launch {
            val receiver = settingsRepository.current().activeReceiver()
            if (receiver == null) {
                if (token != guideToken) {
                    EnigmaLog.service("stale tune ignored")
                    return@launch
                }
                status = UiText.res(R.string.guide_no_receiver)
                return@launch
            }
            val target = streamTargets.target(receiver, channel.reference)
            val streamUrl = target.url
            if (target.internet) {
                // Internet / IPTV: play the URL directly, without waiting for the receiver. The
                // receiver is still asked to zap (best effort) so full screen, which follows the
                // receiver in this mode, shows the same channel.
                EnigmaLog.service("internet stream ${channel.name}: playing directly")
                playback.stopForZap()
                playback.play(streamUrl, force = true, authorization = target.authorization, internet = true)
                launch {
                    runCatching { serviceSource.zap(receiver, channel.reference) }
                        .onFailure { if (it !is CancellationException) EnigmaLog.service("receiver zap for internet channel failed: ${it.javaClass.simpleName}") }
                }
                if (token != guideToken) return@launch
                onStarted()
                bouquetPinned = false
                tunedReference = channel.reference
                shownReference = channel.reference
                displayName = channel.name
                status = UiText.Empty
                guide = null
                return@launch
            }
            val outcome = LiveZapFlow.run(
                token = token,
                isCurrent = { it == guideToken },
                reference = channel.reference,
                streamUrl = streamUrl,
                releaseStream = { playback.stopForZap() },
                zap = { serviceSource.zap(receiver, channel.reference) },
                currentReference = { serviceSource.currentService(receiver).reference },
                startStream = {
                    playback.play(streamUrl, force = true, authorization = target.authorization)
                },
            )
            if (token != guideToken || outcome is LiveZapFlow.Outcome.Stale) return@launch
            when (outcome) {
                LiveZapFlow.Outcome.Stale -> return@launch
                LiveZapFlow.Outcome.Started -> {
                    onStarted()
                    bouquetPinned = false
                    try {
                        val loaded = serviceSource.channelGuide(receiver, channel.reference, channel.name)
                        if (token != guideToken) return@launch
                        guide = loaded
                        val reference = loaded.serviceReference.ifBlank { channel.reference }
                        tunedReference = reference
                        shownReference = reference
                        displayName = loaded.channelName.ifBlank { channel.name }
                        status = UiText.Empty
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (token != guideToken) return@launch
                        status = error.uiMessage(UiText.res(R.string.guide_epg_unavailable))
                    }
                }
                is LiveZapFlow.Outcome.ZapFailed -> {
                    tunedReference = previous
                    status = outcome.error.uiMessage(UiText.res(R.string.guide_start_failed))
                }
                LiveZapFlow.Outcome.TimedOut -> {
                    status = UiText.res(R.string.guide_zap_not_confirmed)
                }
            }
        }
    }

    private suspend fun startPlayback(receiver: ReceiverConfig, reference: String) {
        val target = streamTargets.target(receiver, reference)
        playback.play(target.url, force = true, authorization = target.authorization, internet = target.internet)
    }

    private suspend fun loadGuide() {
        if (tuneJob?.isActive == true) return
        val receiver = settingsRepository.current().activeReceiver()
        if (receiver == null) {
            status = UiText.res(R.string.guide_no_receiver)
            return
        }
        val token = ++guideToken
        runCatching { serviceSource.channelGuide(receiver) }
            .onSuccess { loaded ->
                if (token != guideToken) return@onSuccess
                guide = loaded
                displayName = loaded.channelName
                if (tuneJob?.isActive != true) {
                    tunedReference = loaded.serviceReference
                    shownReference = loaded.serviceReference
                    if (loaded.serviceReference.isNotBlank()) {
                        startPlayback(receiver, loaded.serviceReference)
                    }
                }
                status = UiText.Empty
            }
            .onFailure { error ->
                status = error.uiMessage(UiText.res(R.string.guide_epg_unavailable))
            }
    }

    private fun browsingAwayFromTuned(): Boolean {
        return shownReference.isNotBlank() &&
            tunedReference.isNotBlank() &&
            !BouquetChannels.sameService(shownReference, tunedReference)
    }

    private suspend fun reloadShownGuide() {
        if (tuneJob?.isActive == true) return
        val receiver = settingsRepository.current().activeReceiver() ?: return
        val reference = shownReference
        val name = displayName
        if (reference.isBlank()) return
        val token = ++guideToken
        runCatching { serviceSource.channelGuide(receiver, reference, name) }
            .onSuccess { loaded ->
                if (token != guideToken) return@onSuccess
                guide = loaded
                displayName = loaded.channelName.ifBlank { name }
            }
    }

    fun previewBouquet(bouquet: BouquetEntry) {
        if (bouquet.reference == selectedBouquetReference && bouquetLoaded) return
        bouquetPinned = true
        lastPressedReference = ""
        selectedBouquetReference = bouquet.reference
        val token = ++bouquetToken
        bouquetJob?.cancel()
        bouquetJob = viewModelScope.launch {
            delay(500)
            if (token != bouquetToken) return@launch
            showBouquet(bouquet, token)
        }
    }

    private suspend fun showBouquet(bouquet: BouquetEntry, token: Int) {
        val receiver = settingsRepository.current().activeReceiver() ?: return
        runCatching { serviceSource.channelsInBouquet(receiver, bouquet.reference) }
            .onSuccess { loaded ->
                if (token != bouquetToken) return@onSuccess
                bouquetName = bouquet.name
                channels = loaded
                bouquetLoaded = true
                status = UiText.Empty
                EnigmaLog.service("bouquet ${bouquet.name} channels=${loaded.size}")
            }
            .onFailure { error ->
                if (token != bouquetToken) return@onFailure
                status = error.uiMessage(UiText.res(R.string.guide_channels_unavailable))
            }
    }

    private suspend fun loadBouquet() {
        val receiver = settingsRepository.current().activeReceiver() ?: return
        if (!bouquetPinned) {
            runCatching { serviceSource.televisionBouquets(receiver) }
                .onSuccess { bouquets = it }
        }
        runCatching { serviceSource.currentBouquetChannels(receiver) }
            .onSuccess { loaded ->
                if (bouquetPinned) return@onSuccess
                if (loaded.channels.isEmpty()) return@onSuccess
                bouquetName = loaded.bouquetName
                channels = loaded.channels
                selectedBouquetReference = loaded.bouquetReference
                bouquetLoaded = true
                EnigmaLog.service("bouquet ${loaded.bouquetName} channels=${loaded.channels.size}")
            }
            .onFailure { error ->
                if (status.isEmpty()) status = error.uiMessage(UiText.res(R.string.guide_channels_unavailable))
            }
    }

    private companion object {
        const val NOTICE_MS = 4_000L
        const val NOTICE_LONG_MS = 8_000L
    }
}
