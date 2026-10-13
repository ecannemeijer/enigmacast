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
import com.enigmacast.domain.recording.forReceiver
import com.enigmacast.domain.service.BouquetChannels
import com.enigmacast.domain.service.LiveZapFlow
import com.enigmacast.domain.service.StreamReuse
import com.enigmacast.domain.tuner.StreamHealth
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
import com.enigmacast.util.isNotEmpty
import com.enigmacast.util.uiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Watch TV and Radio each keep their own bouquet list. */
enum class GuideMedium { Television, Radio }

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
    private val guideCache: com.enigmacast.domain.sync.GuideCacheReader,
    private val picons: PiconRepository,
    private val quality: StreamQualityController,
    private val reminders: ReminderScheduler,
    private val recordings: com.enigmacast.live.RecordingBook,
) : ViewModel() {
    private var medium = GuideMedium.Television
    private val remembered = mutableMapOf<GuideMedium, GuideSide>()
    private var mediumJob: Job? = null
    var guide by mutableStateOf<ChannelGuide?>(null)
        private set
    var channels by mutableStateOf<List<BouquetEntry>>(emptyList())
        private set
    /** True only while the first channel list is fetched. The wait dialog uses this. */
    var loadingChannels by mutableStateOf(false)
        private set
    /** Which step the wait dialog is on: bouquets, channels or programme information. */
    var loadingLabel by mutableStateOf(UiText.Empty)
        private set
    /** Finished share of the load steps, from 0 to 1. */
    var loadingProgress by mutableStateOf(0f)
        private set
    private var sideLoading = false
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
    private var watchedReceiverId: String? = null
    private var receiverWatchStarted = false

    /** True while the guide drives this device's own stream instead of the receiver. */
    val ownTuner: Boolean get() = mode == StreamMode.OWN_TUNER

    init {
        viewModelScope.launch {
            settingsRepository.settings.map { it.streamMode }.distinctUntilChanged().collect(::onModeChanged)
        }
        viewModelScope.launch {
            settingsRepository.settings.map { it.activeReceiver()?.id }.distinctUntilChanged().collect { id ->
                if (!receiverWatchStarted) {
                    receiverWatchStarted = true
                    watchedReceiverId = id
                    return@collect
                }
                if (id == watchedReceiverId) return@collect
                watchedReceiverId = id
                onReceiverChanged(id)
            }
        }
        viewModelScope.launch {
            session.state.collect(::mirror)
        }
        viewModelScope.launch {
            quality.notices.collect { showNotice(it, NOTICE_LONG_MS) }
        }
        viewModelScope.launch {
            session.notices.collect { showNotice(it, NOTICE_LONG_MS) }
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
            combine(
                recordings.items,
                settingsRepository.settings.map { it.activeReceiver()?.id.orEmpty() }.distinctUntilChanged(),
            ) { list, id ->
                list.forReceiver(id)
                    .filter { it.status == com.enigmacast.domain.recording.RecordingStatus.RECORDING }
                    .map { it.reference }
                    .toSet()
            }.collect { recordingReferences = it }
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

    /** OK on TV Guide: the highlight on the first channel, at the current time. */
    fun focusFirstInGrid() {
        grid.focusFirst()
        lastPressedReference = ""
    }

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

    fun confirmRecord(place: com.enigmacast.domain.model.RecordingTarget, all: Boolean = false) {
        val ask = recordAsk ?: return
        recordAsk = null
        viewModelScope.launch { showNotice(recordings.schedule(ask.copy(place = place, allUpcoming = all)), NOTICE_LONG_MS) }
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

    /** Another box was chosen: drop this box's bouquet and load the new one. */
    private fun onReceiverChanged(id: String?) {
        tuneJob?.cancel()
        previewJob?.cancel()
        bouquetJob?.cancel()
        mediumJob?.cancel()
        bouquetToken += 1
        guideToken += 1
        remembered.clear()
        medium = GuideMedium.Television
        bouquetLoaded = false
        bouquetPinned = false
        bouquets = emptyList()
        channels = emptyList()
        bouquetName = ""
        selectedBouquetReference = ""
        tunedReference = ""
        displayName = ""
        guide = null
        shownReference = ""
        status = UiText.Empty
        askWake = false
        leaveOff = false
        powerCheckStarted = false
        powerKnown = false
        lastPressedReference = ""
        session.bind(id)
        if (id != null) refresh()
    }

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

    /**
     * Shows [next] without zapping. Each side keeps the bouquet it was on, and the playing
     * list is not copied onto the other side.
     */
    fun show(next: GuideMedium) {
        if (next != medium) {
            rememberCurrent()
            medium = next
            mediumJob?.cancel()
            val saved = remembered[next]
            if (saved != null && saved.loaded) {
                restore(saved)
                bouquetPinned = !playingOn(next)
                return
            }
            bouquetPinned = true
            bouquets = emptyList()
            channels = emptyList()
            bouquetName = ""
            selectedBouquetReference = ""
            bouquetLoaded = false
        }
        loadMediumIfNeeded()
    }

    private fun loadMediumIfNeeded() {
        if (!GuideSideLoad.needsBouquetList(bouquets.size, mediumJob?.isActive == true)) return
        val which = medium
        mediumJob = viewModelScope.launch { ensureMediumLoaded(which) }
    }

    private suspend fun ensureMediumLoaded(which: GuideMedium) {
        val receiver = settingsRepository.current().activeReceiver() ?: return
        val list = runCatching { bouquetsFor(receiver, which) }.getOrDefault(emptyList())
        if (which != medium) return
        bouquets = list
        // The channels already came from the stream or Watch; only the bouquet list was missing.
        if (!GuideSideLoad.needsChannels(bouquetLoaded) || (bouquetPinned && selectedBouquetReference.isNotBlank())) {
            rememberCurrent()
            return
        }
        if (mode != StreamMode.OWN_TUNER) {
            bouquetPinned = false
            if (which == GuideMedium.Radio) {
                list.firstOrNull()?.let { entry ->
                    bouquetPinned = true
                    selectedBouquetReference = entry.reference
                    showBouquet(entry, ++bouquetToken)
                }
            }
            return
        }
        if (playingOn(which)) {
            val state = session.state.value
            channels = state.channels
            bouquetName = state.bouquetName
            selectedBouquetReference = state.bouquetReference
            bouquetLoaded = state.channels.isNotEmpty()
            bouquetPinned = false
            rememberCurrent()
            return
        }
        val state = session.state.value
        if (state.loadingChannels || state.channels.isEmpty()) {
            // The last channel is still loading. mirror() fills this side when it settles.
            bouquetPinned = false
            return
        }
        val entry = list.firstOrNull() ?: return
        bouquetPinned = true
        selectedBouquetReference = entry.reference
        showBouquet(entry, ++bouquetToken)
        rememberCurrent()
    }

    private suspend fun bouquetsFor(receiver: ReceiverConfig, which: GuideMedium): List<BouquetEntry> = when (which) {
        GuideMedium.Television -> serviceSource.televisionBouquets(receiver)
        GuideMedium.Radio -> serviceSource.radioBouquets(receiver)
    }

    private fun playingOn(which: GuideMedium): Boolean {
        val reference = session.state.value.bouquetReference
        if (reference.isBlank() || session.state.value.channels.isEmpty()) return false
        return BouquetChannels.isRadioBouquet(reference) == (which == GuideMedium.Radio)
    }

    private fun rememberCurrent() {
        if (!bouquetLoaded && bouquets.isEmpty()) return
        remembered[medium] = GuideSide(bouquets, channels, bouquetName, selectedBouquetReference, bouquetLoaded)
    }

    private fun restore(saved: GuideSide) {
        bouquets = saved.bouquets
        channels = saved.channels
        bouquetName = saved.bouquetName
        selectedBouquetReference = saved.selectedBouquetReference
        bouquetLoaded = saved.loaded
    }

    private fun mirror(state: OwnTunerState) {
        if (mode != StreamMode.OWN_TUNER) return
        if (state.loadingChannels) {
            loadingChannels = true
            if (state.loadingLabel.isNotEmpty()) loadingLabel = state.loadingLabel
            loadingProgress = state.loadingProgress
        } else if (!sideLoading) {
            loadingChannels = false
            loadingLabel = UiText.Empty
            loadingProgress = 0f
        }
        val sameSide = playingOn(medium)
        if (!bouquetPinned && sameSide && state.channels.isNotEmpty()) {
            channels = state.channels
            bouquetName = state.bouquetName
            selectedBouquetReference = state.bouquetReference
            bouquetLoaded = true
            rememberCurrent()
        } else if (!sameSide && !bouquetPinned && channels.isEmpty() && bouquets.isNotEmpty() &&
            !state.loadingChannels && state.channels.isNotEmpty()
        ) {
            bouquets.firstOrNull()?.let { entry ->
                bouquetPinned = true
                selectedBouquetReference = entry.reference
                viewModelScope.launch { showBouquet(entry, ++bouquetToken) }
            }
        }
        val channel = state.channel
        if (channel != null && sameSide) {
            val browsing = browsingAwayFromTuned()
            tunedReference = channel.reference
            if (!browsing) {
                shownReference = channel.reference
                displayName = channel.name
                guide = state.guide?.takeIf { BouquetChannels.sameService(it.serviceReference, channel.reference) }
            }
        }
        status = when {
            state.loadingChannels -> UiText.Empty
            state.tuner.status == TunerStatus.PLAYING || state.tuner.status == TunerStatus.IDLE -> UiText.Empty
            state.tuner.status == TunerStatus.FULL -> UiText.Joined(
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
        loadMediumIfNeeded()
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
            loadFollowing()
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
            loadFollowing()
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
        viewModelScope.launch { loadFollowing() }
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

    /**
     * Live TV in the menu: back to the running stream full screen, else play the last channel
     * of this receiver (or the first channel of the list) and go full screen.
     */
    fun openLive(onFullscreen: () -> Unit) {
        val running = session.state.value.channel != null &&
            playback.state.value.playbackState.let { it != "IDLE" && it != "ENDED" }
        if (running) {
            onFullscreen()
            return
        }
        viewModelScope.launch {
            val settings = settingsRepository.current()
            val receiver = settings.activeReceiver()
            val last = receiver?.let { com.enigmacast.domain.model.ChannelMemory.lastFor(settings, it.id) }
            val entry = last?.takeIf { it.reference.isNotBlank() }
                ?.let { BouquetEntry(name = it.name, reference = it.reference) }
                ?: channels.firstOrNull()
            if (entry == null) {
                onFullscreen()
                return@launch
            }
            lastPressedReference = ""
            tune(entry, onFullscreen, onFullscreen)
        }
    }

    /**
     * Watch from anywhere (Home, guide, Soccer, search): when the channel is not in the list on
     * screen, first switch to the synced bouquet that holds it, so the player's channel list
     * (Left) shows that bouquet with the channel selected.
     */
    fun tune(channel: BouquetEntry, onStarted: () -> Unit, onFullscreen: () -> Unit) {
        if (channels.any { BouquetChannels.sameService(it.reference, channel.reference) }) {
            tuneNow(channel, onStarted, onFullscreen)
            return
        }
        viewModelScope.launch {
            val aligned = runCatching { alignBouquetTo(channel) }.getOrNull()
            tuneNow(aligned ?: channel, onStarted, onFullscreen)
        }
    }

    /** Loads the bouquet holding [channel] (Room first, receiver as fallback); returns the list's entry for it. */
    private suspend fun alignBouquetTo(channel: BouquetEntry): BouquetEntry? {
        val receiver = settingsRepository.current().activeReceiver() ?: return null
        val bouquet = guideCache.bouquetOf(receiver, channel.reference) ?: return null
        val list = guideCache.channelsInBouquet(receiver, bouquet.reference)
            ?: runCatching { serviceSource.channelsInBouquet(receiver, bouquet.reference) }.getOrNull()
            ?: return null
        val entry = list.firstOrNull { BouquetChannels.sameService(it.reference, channel.reference) } ?: return null
        if (BouquetChannels.isRadioBouquet(bouquet.reference) != (medium == GuideMedium.Radio)) {
            rememberCurrent()
            medium = if (BouquetChannels.isRadioBouquet(bouquet.reference)) GuideMedium.Radio else GuideMedium.Television
        }
        bouquetJob?.cancel()
        ++bouquetToken
        selectedBouquetReference = bouquet.reference
        bouquetName = bouquet.name
        channels = list
        bouquetLoaded = true
        rememberCurrent()
        EnigmaLog.service("watch: bouquet ${bouquet.name} for ${entry.name}")
        return entry
    }

    private fun tuneNow(channel: BouquetEntry, onStarted: () -> Unit, onFullscreen: () -> Unit) {
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
            val playing = playback.health.value == StreamHealth.PLAYING &&
                playback.hasOpenStream() &&
                !playback.isFilePlayback()
            if (StreamReuse.alreadyPlaying(previous, channel.reference, playing)) {
                EnigmaLog.service("same channel, stream kept: ${channel.name}")
                if (token != guideToken) return@launch
                onStarted()
                bouquetPinned = false
                tunedReference = channel.reference
                shownReference = channel.reference
                displayName = channel.name
                status = UiText.Empty
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
                        EnigmaLog.service("programme load failed: ${error.javaClass.simpleName}")
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
                    if (loaded.serviceReference.isNotBlank() && channels.isNotEmpty()) {
                        startPlayback(receiver, loaded.serviceReference)
                    }
                }
                status = UiText.Empty
            }
            .onFailure { error ->
                if (error is CancellationException) throw error
                EnigmaLog.service("programme load failed: ${error.javaClass.simpleName}")
            }
    }

    /** First follow-mode load keeps the wait dialog up and names each step. Later refreshes stay quiet. */
    private suspend fun loadFollowing() {
        val initial = mode != StreamMode.OWN_TUNER && (!bouquetLoaded || channels.isEmpty())
        if (initial) {
            sideLoading = true
            loadingChannels = true
            loadingLabel = UiText.res(R.string.load_bouquets)
            loadingProgress = 0.15f
        }
        try {
            if (!bouquetLoaded || channels.isEmpty()) loadBouquet()
            if (browsingAwayFromTuned()) {
                reloadShownGuide()
            } else {
                if (initial) {
                    loadingLabel = UiText.res(R.string.load_programme)
                    loadingProgress = 0.75f
                }
                loadGuide()
            }
        } finally {
            if (initial) {
                sideLoading = false
                loadingChannels = false
                loadingLabel = UiText.Empty
                loadingProgress = 0f
            }
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

    /** Search picked a bouquet: show its channels in the guide without zapping. */
    fun openBouquet(bouquet: BouquetEntry) {
        val next = if (BouquetChannels.isRadioBouquet(bouquet.reference)) GuideMedium.Radio else GuideMedium.Television
        if (next != medium) {
            rememberCurrent()
            medium = next
            mediumJob?.cancel()
            remembered[next]?.let(::restore)
        }
        bouquetPinned = true
        lastPressedReference = ""
        selectedBouquetReference = bouquet.reference
        bouquetName = bouquet.name
        val token = ++bouquetToken
        bouquetJob?.cancel()
        bouquetJob = viewModelScope.launch { showBouquet(bouquet, token) }
    }

    /** A search tune should follow the session again instead of a pinned bouquet. */
    fun releaseBouquetPin() {
        bouquetPinned = false
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
                rememberCurrent()
            }
            .onFailure { error ->
                if (token != bouquetToken) return@onFailure
                if (error is CancellationException) throw error
                EnigmaLog.service("bouquet ${bouquet.name} failed: ${error.javaClass.simpleName}")
                status = UiText.res(R.string.guide_channels_unavailable)
            }
    }

    private suspend fun loadBouquet() {
        val receiver = settingsRepository.current().activeReceiver() ?: return
        if (!bouquetPinned) {
                if (sideLoading) {
                    loadingLabel = UiText.res(R.string.load_bouquets)
                    loadingProgress = 0.15f
                }
                runCatching { bouquetsFor(receiver, medium) }.onSuccess { bouquets = it }
            }
            if (medium == GuideMedium.Radio) {
                if (!bouquetLoaded) {
                    bouquets.firstOrNull()?.let { first ->
                        bouquetPinned = true
                        selectedBouquetReference = first.reference
                        showBouquet(first, ++bouquetToken)
                    }
                }
                return
            }
            if (sideLoading) {
                loadingLabel = UiText.res(R.string.load_channels)
                loadingProgress = 0.45f
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
                    if (error is CancellationException) throw error
                    EnigmaLog.service("channel list failed: ${error.javaClass.simpleName}")
                    if (status.isEmpty()) status = UiText.res(R.string.guide_channels_unavailable)
                }
    }

    private companion object {
        const val NOTICE_MS = 4_000L
        const val NOTICE_LONG_MS = 8_000L
    }
}

private data class GuideSide(
    val bouquets: List<BouquetEntry>,
    val channels: List<BouquetEntry>,
    val bouquetName: String,
    val selectedBouquetReference: String,
    val loaded: Boolean,
)

/**
 * When a guide side fetches what. The channels can arrive first (the own-tuner stream or Watch fill
 * them in), so a loaded channel list must not stop the bouquet list from loading: that left the
 * TV guide without bouquets.
 */
internal object GuideSideLoad {
    fun needsBouquetList(bouquetCount: Int, loading: Boolean): Boolean = bouquetCount == 0 && !loading
    fun needsChannels(channelsLoaded: Boolean): Boolean = !channelsLoaded
}
