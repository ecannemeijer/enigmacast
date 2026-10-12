package com.enigmacast.ui.remote

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.text.Cue
import com.enigmacast.R
import com.enigmacast.connection.ConnectionCoordinator
import com.enigmacast.data.network.ReceiverRouter
import com.enigmacast.data.openwebif.OpenWebifEndpoints
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.epg.ProgrammePrompt
import com.enigmacast.domain.model.AppSettings
import com.enigmacast.domain.model.ChannelMemory
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ConnectionState
import com.enigmacast.domain.model.DebugInfo
import com.enigmacast.domain.model.EnigmaService
import com.enigmacast.domain.model.LastChannel
import com.enigmacast.domain.model.OsdInfoFields
import com.enigmacast.domain.model.OsdSkinId
import com.enigmacast.domain.model.PlayerState
import com.enigmacast.domain.network.PlainHttpRisk
import com.enigmacast.domain.network.ReceiverRoute
import com.enigmacast.domain.network.ReceiverRouting
import com.enigmacast.domain.network.StreamLevel
import com.enigmacast.domain.osd.GrabFormat
import com.enigmacast.domain.osd.OsdConfig
import com.enigmacast.domain.osd.OsdController
import com.enigmacast.domain.osd.OsdPollState
import com.enigmacast.domain.osdinfo.OsdInfo
import com.enigmacast.domain.osdinfo.OsdInfoBuilder
import com.enigmacast.domain.osdinfo.OsdInfoInput
import com.enigmacast.domain.osdinfo.PlayerFacts
import com.enigmacast.domain.player.PictureMode
import com.enigmacast.domain.recording.forReceiver
import com.enigmacast.domain.remote.EnigmaKey
import com.enigmacast.domain.remote.EnigmaKeyMapper
import com.enigmacast.domain.service.BouquetChannels
import com.enigmacast.domain.service.ServiceStreams
import com.enigmacast.domain.service.ServiceMonitor
import com.enigmacast.domain.source.OsdSource
import com.enigmacast.domain.source.ReceiverInfoSource
import com.enigmacast.domain.source.RemoteControlSource
import com.enigmacast.domain.source.ServiceStateSource
import com.enigmacast.domain.tuner.ChannelListNavigation
import com.enigmacast.domain.tuner.ListPane
import com.enigmacast.domain.tuner.NowEvent
import com.enigmacast.domain.tuner.NowEvents
import com.enigmacast.domain.tuner.NumberEntry
import com.enigmacast.domain.tuner.OwnKeyAction
import com.enigmacast.domain.tuner.OwnTunerKeys
import com.enigmacast.domain.tuner.StreamModePolicy
import com.enigmacast.live.GuideRepository
import com.enigmacast.live.NowEpgRepository
import com.enigmacast.live.OsdInfoRepository
import com.enigmacast.live.OwnTunerSession
import com.enigmacast.live.OwnTunerState
import com.enigmacast.live.PiconRepository
import com.enigmacast.live.QualityState
import com.enigmacast.live.ReminderScheduler
import com.enigmacast.live.StreamQualityController
import com.enigmacast.live.StreamTargets
import com.enigmacast.live.TunerStatus
import com.enigmacast.osd.AndroidOsdAnalyzer
import com.enigmacast.playback.AppSleepTimer
import com.enigmacast.playback.AudioSync
import com.enigmacast.playback.LivePlaybackController
import com.enigmacast.playback.SleepEvent
import com.enigmacast.playback.StreamStats
import com.enigmacast.playback.TrackMenu
import com.enigmacast.remote.RemoteInputBus
import com.enigmacast.remote.RemoteKeyDispatcher
import com.enigmacast.util.DebugFlags
import com.enigmacast.util.EnigmaLog
import com.enigmacast.util.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OsdFrame(
    val bitmap: Bitmap,
    val hash: Long,
    val width: Int,
    val height: Int,
    val downloadDurationMs: Long,
)

data class RemoteDisplayUiState(
    val connectionState: ConnectionState = ConnectionState.Idle,
    val currentService: EnigmaService? = null,
    val osdFrame: OsdFrame? = null,
    val playerState: PlayerState = PlayerState(),
    val debugInfo: DebugInfo = DebugInfo(),
    val debugOverlayEnabled: Boolean = false,
    val osdDebugBorders: Boolean = false,
    val showExitDialog: Boolean = false,
    val osdState: OsdPollState = OsdPollState.IDLE,
    val cues: List<Cue> = emptyList(),
    val subtitlesAvailable: Boolean = false,
    /** Own-tuner mode: the app draws its own channel banner and list instead of the receiver's OSD. */
    val ownTuner: Boolean = false,
    val own: OwnTunerState = OwnTunerState(),
    val banner: Boolean = false,
    val details: Boolean = false,
    val channelList: Boolean = false,
    /** The bouquet column left of the channel list (Left in the list). */
    val bouquetPanel: Boolean = false,
    val bouquets: List<BouquetEntry> = emptyList(),
    /** Now on air for the list's bouquet, keyed by `NowEvents.key`. */
    val nowEvents: Map<String, NowEvent> = emptyMap(),
    /** Bumped whenever the channel list should take the focus (open, bouquet chosen, bouquet column closed). */
    val listFocusToken: Int = 0,
    val numberEntry: String = "",
    val notice: UiText = UiText.Empty,
    /** The banner came up because of a zap; Down then zaps on instead of opening the quick bar. */
    val bannerFromZap: Boolean = false,
    /** The quick options bar under the banner (Down on the banner, or hold Info when following the receiver). */
    val quickBar: Boolean = false,
    /** Bumped when the quick bar opens, so its first tile takes the focus. */
    val quickBarToken: Int = 0,
    /** Unified search over the picture, opened from the first quick-bar tile. */
    val searchOpen: Boolean = false,
    /** Bumped when search opens, so the field takes the focus. */
    val searchToken: Int = 0,
    val tracks: TrackMenu = TrackMenu(),
    val stats: StreamStats = StreamStats(),
    val pictureMode: PictureMode = PictureMode.AUTO,
    val hdmiPassthrough: Boolean = false,
    /** The audio delay of the output in use (HDMI passthrough or stereo). */
    val audioDelayMs: Int = 0,
    val audioDelayPassthrough: Boolean = false,
    val sleepEndsAtMs: Long = 0L,
    val recent: List<LastChannel> = emptyList(),
    val receiverAddress: String = "",
    /** How the receiver is reached and what crosses the internet unencrypted on that route. */
    val route: ReceiverRoute = ReceiverRoute.HOME,
    val routeRisk: PlainHttpRisk = PlainHttpRisk.NONE,
    val quality: QualityState = QualityState(),
    /** Channel list: the channel whose programme guide is open right of the list (Right on a channel). */
    val epgChannel: BouquetEntry? = null,
    val epgEvents: List<GuideEvent> = emptyList(),
    val epgLoading: Boolean = false,
    /** The channel the list focuses; blank means the channel that plays. */
    val listFocusReference: String = "",
    val programmePrompt: ProgrammePrompt? = null,
    /** Confirm before a recording is stored. */
    val recordAsk: com.enigmacast.domain.recording.RecordAsk? = null,
    /** Title of a capture that is being written now, blank when nothing is recording. */
    val recordingTitle: String = "",
    /** Channel names of the captures that are being written now. */
    val recordingChannel: String = "",
    /** Service references of the channels that are being recorded. */
    val recordingReferences: Set<String> = emptySet(),
    /** The recording playback bar is open. */
    val fileBar: Boolean = false,
    val fileTitle: String = "",
    /** Programmes with AutoSwitch set ([GuideEvent.programmeKey]). */
    val autoSwitchKeys: Set<String> = emptySet(),
    /** Receiver facts for the channel banner. The skin draws [osdInfo]; this stays independent of the look. */
    val osdInfo: OsdInfo = OsdInfo.Empty,
    val osdSkin: OsdSkinId = OsdSkinId.STANDARD,
    val osdInfoFields: OsdInfoFields = OsdInfoFields(),
    /** 0 is a solid panel, 100 is a clear one. Channel banner, channel list and receiver OSD. */
    val panelTransparency: Int = 10,
)


@HiltViewModel
class RemoteDisplayViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val endpoints: OpenWebifEndpoints,
    private val osdSource: OsdSource,
    private val remoteSource: RemoteControlSource,
    private val serviceSource: ServiceStateSource,
    private val infoSource: ReceiverInfoSource,
    private val playback: LivePlaybackController,
    private val inputBus: RemoteInputBus,
    private val ownSession: OwnTunerSession,
    private val picons: PiconRepository,
    private val nowEpg: NowEpgRepository,
    private val sleepTimer: AppSleepTimer,
    private val guideRepository: GuideRepository,
    private val streamTargets: StreamTargets,
    private val router: ReceiverRouter,
    private val quality: StreamQualityController,
    private val reminders: ReminderScheduler,
    private val osdInfoRepository: OsdInfoRepository,
    private val recordings: com.enigmacast.live.RecordingBook,
) : ViewModel() {
    private val _ui = MutableStateFlow(RemoteDisplayUiState())
    val uiState: StateFlow<RemoteDisplayUiState> = _ui.asStateFlow()
    private val analyzer = AndroidOsdAnalyzer()
    private var settingsSnapshot = AppSettings()
    private var sessionSignature: List<Any?>? = null
    private var session: Job? = null
    private var osdController: OsdController? = null
    private var serviceMonitor: ServiceMonitor? = null
    private var keys: RemoteKeyDispatcher? = null
    private var connection: ConnectionCoordinator? = null
    private var lastKey = ""
    private var backDownAt = 0L
    private var dashboardLeaveSent = false
    private var backPressToken = 0
    private var releasedBackToken = 0
    private var backJob: Job? = null
    private var okMenuSent = false
    private var okJob: Job? = null
    private var blueTextSent = false
    private var blueJob: Job? = null
    private var infoQuickBar = false
    private var infoJob: Job? = null
    var onLeaveToDashboard: (() -> Unit)? = null
    var onLeaveToMyItems: ((String) -> Unit)? = null
    private var bannerJob: Job? = null
    private var fileBarJob: Job? = null
    private var osdInfoJob: Job? = null
    private var osdInfoKey: String? = null
    private var numberJob: Job? = null
    private var noticeJob: Job? = null
    private var nowEpgJob: Job? = null
    private var bouquetJob: Job? = null
    private var epgJob: Job? = null
    private val numberEntry = NumberEntry()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        playback.attach(viewModelScope)
        inputBus.handler = ::handleKey
        viewModelScope.launch {
            playback.state.collect { state ->
                if (!state.filePlayback && _ui.value.fileBar) hideFileBar()
            }
        }
        viewModelScope.launch {
            recordings.nowPlaying.collect { item ->
                _ui.update { it.copy(fileTitle = item?.title.orEmpty()) }
            }
        }
        viewModelScope.launch {
            recordings.items.collect {
                publishRunningRecordings()
            }
        }
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                settingsSnapshot = settings
                DebugFlags.verbose = settings.debug.verboseLogging
                playback.updateSettings(settings.playback)
                _ui.update {
                    it.copy(
                        debugOverlayEnabled = settings.debug.overlay,
                        osdDebugBorders = settings.osd.debugBorders,
                        osdSkin = settings.osdSkin,
                        osdInfoFields = settings.osdInfo,
                        panelTransparency = settings.osd.transparencyPercent.coerceIn(0, 100),
                        hdmiPassthrough = settings.playback.hdmiPassthrough,
                        recent = ChannelMemory.recentFor(settings, settings.activeReceiver()?.id.orEmpty()),
                        receiverAddress = settings.activeReceiver()?.let { receiver -> "${receiver.host}:${receiver.streamingPort}" }.orEmpty(),
                    )
                }
                refreshPlaybackOptions()
                publishRunningRecordings()
                val signature = listOf(
                    settings.activeReceiver(),
                    settings.osd.copy(debugBorders = false, transparencyPercent = 0),
                    settings.remote,
                    settings.streamMode,
                )
                if (signature != sessionSignature) {
                    sessionSignature = signature
                    restart(settings)
                }
                refreshDebug()
            }
        }
        registerNetwork()
        viewModelScope.launch {
            playback.cues.collect { cues -> _ui.update { it.copy(cues = cues) } }
        }
        viewModelScope.launch {
            playback.subtitlesAvailable.collect { available ->
                _ui.update { it.copy(subtitlesAvailable = available) }
            }
        }
        viewModelScope.launch {
            playback.tracks.collect { tracks -> _ui.update { it.copy(tracks = tracks) } }
        }
        viewModelScope.launch {
            playback.stats.collect { stats -> _ui.update { it.copy(stats = stats) } }
        }
        viewModelScope.launch {
            playback.audioPassthroughActive.collect { refreshPlaybackOptions() }
        }
        viewModelScope.launch {
            quality.state.collect { state -> _ui.update { it.copy(quality = state) } }
        }
        viewModelScope.launch {
            quality.notices.collect { showNotice(it, NOTICE_LONG_MS) }
        }
        viewModelScope.launch {
            playback.timeshiftFull.collect {
                if (_ui.value.ownTuner) showNotice(UiText.res(R.string.timeshift_full), NOTICE_LONG_MS)
            }
        }
        viewModelScope.launch {
            // Following the receiver there is no tuner overlay; say it when an internet stream gave up.
            playback.health.collect { health ->
                if (_ui.value.ownTuner) return@collect
                internetProblem(health)?.let { showNotice(it, NOTICE_LONG_MS) }
            }
        }
        viewModelScope.launch {
            reminders.scheduledKeys.collect { keys -> _ui.update { it.copy(autoSwitchKeys = keys) } }
        }
        viewModelScope.launch {
            // Following the receiver: reopen the receiver's channel with the new quality.
            quality.restarts.collect {
                if (_ui.value.ownTuner || playback.isFilePlayback()) return@collect
                val active = settingsSnapshot.activeReceiver() ?: return@collect
                val reference = _ui.value.currentService?.reference?.takeIf { it.isNotBlank() } ?: return@collect
                val target = streamTargets.target(active, reference)
                playback.play(target.url, force = true, authorization = target.authorization, internet = target.internet)
            }
        }
        viewModelScope.launch {
            router.status.collect { status ->
                val receiver = settingsSnapshot.activeReceiver()
                val risk = receiver?.let { ReceiverRouting.plainHttpRisk(it, status.route) } ?: PlainHttpRisk.NONE
                val previous = _ui.value
                _ui.update { it.copy(route = status.route, routeRisk = risk) }
                // Say it once when the app switches to an unencrypted internet route.
                if (risk != PlainHttpRisk.NONE && (previous.route != status.route || previous.routeRisk == PlainHttpRisk.NONE)) {
                    showNotice(UiText.res(R.string.route_unencrypted_notice), NOTICE_LONG_MS)
                }
            }
        }
        viewModelScope.launch {
            sleepTimer.endsAtMs.collect { endsAt -> _ui.update { it.copy(sleepEndsAtMs = endsAt) } }
        }
        viewModelScope.launch {
            sleepTimer.events.collect { event ->
                if (event == SleepEvent.WARNING) showNotice(UiText.res(R.string.sleep_app_warning), NOTICE_LONG_MS)
            }
        }
    }

    /** Picture mode of the channel that plays and the audio delay of the output in use. */
    private fun refreshPlaybackOptions() {
        val reference = _ui.value.currentService?.reference.orEmpty()
        val mode = if (reference.isBlank()) PictureMode.AUTO else settingsSnapshot.pictureModes[NowEvents.key(reference)] ?: PictureMode.AUTO
        val passthrough = playback.audioPassthroughActive.value ?: settingsSnapshot.playback.hdmiPassthrough
        val delay = if (passthrough) settingsSnapshot.playback.audioDelayPassthroughMs else settingsSnapshot.playback.audioDelayPcmMs
        _ui.update { it.copy(pictureMode = mode, audioDelayMs = delay, audioDelayPassthrough = passthrough) }
    }

    fun openQuickBar() {
        bannerJob?.cancel()
        _ui.update { it.copy(quickBar = true, quickBarToken = it.quickBarToken + 1) }
        watchOsdInfo()
    }

    fun openSearch() {
        _ui.update { it.copy(searchOpen = true, searchToken = it.searchToken + 1) }
    }

    /** Back from search: the bar stays, and its first tile (Search) takes the focus again. */
    fun closeSearch() {
        _ui.update { it.copy(searchOpen = false, quickBarToken = it.quickBarToken + 1) }
    }

    /** A channel or recording started: leave the search panel and the bar. */
    fun finishSearchPlayback() {
        _ui.update { it.copy(searchOpen = false, quickBar = false) }
        if (_ui.value.ownTuner && _ui.value.banner) showBanner()
    }

    fun noteBouquet(name: String) {
        closeSearch()
        showNotice(UiText.res(R.string.search_bouquet_set, name))
    }

    fun showFileBar() {
        _ui.update { it.copy(fileBar = true) }
        armFileBarHide()
    }

    fun touchFileBar() {
        if (_ui.value.fileBar) armFileBarHide()
    }

    fun hideFileBar() {
        fileBarJob?.cancel()
        _ui.update { it.copy(fileBar = false) }
    }

    fun openFileOptions() {
        touchFileBar()
        openQuickBar()
    }

    fun seekFile(positionMs: Long) {
        playback.seekFile(positionMs)
        touchFileBar()
    }

    fun toggleFilePause() {
        playback.toggleFilePause()
        touchFileBar()
    }

    private fun armFileBarHide() {
        fileBarJob?.cancel()
        fileBarJob = viewModelScope.launch {
            delay(8_000)
            if (!_ui.value.quickBar) _ui.update { it.copy(fileBar = false) }
        }
    }

    /** Back on the bar: close it; the banner (own tuner) stays for its usual few seconds. */
    fun closeQuickBar() {
        if (!_ui.value.quickBar) return
        _ui.update { it.copy(quickBar = false, searchOpen = false) }
        if (_ui.value.ownTuner && _ui.value.banner) showBanner()
    }

    fun chooseSubtitles(id: String?) {
        playback.chooseSubtitles(id)
        viewModelScope.launch {
            settingsRepository.update { it.copy(playback = it.playback.copy(subtitlesEnabled = id != null)) }
        }
    }

    fun chooseAudio(id: String) = playback.selectAudio(id)

    /** Switching between stereo and passthrough builds a new player, so the picture restarts briefly. */
    fun setPassthrough(enabled: Boolean) {
        if (enabled == settingsSnapshot.playback.hdmiPassthrough) return
        viewModelScope.launch {
            settingsRepository.update { it.copy(playback = it.playback.copy(hdmiPassthrough = enabled)) }
        }
    }

    fun choosePictureMode(mode: PictureMode) {
        val reference = _ui.value.currentService?.reference.orEmpty()
        if (reference.isBlank()) return
        val key = NowEvents.key(reference)
        _ui.update { it.copy(pictureMode = mode) }
        viewModelScope.launch {
            settingsRepository.update { settings ->
                val modes = if (mode == PictureMode.AUTO) settings.pictureModes - key else settings.pictureModes + (key to mode)
                settings.copy(pictureModes = modes)
            }
        }
    }

    fun setAudioDelay(ms: Int) {
        val passthrough = _ui.value.audioDelayPassthrough
        val value = AudioSync.clamp(ms)
        _ui.update { it.copy(audioDelayMs = value) }
        viewModelScope.launch {
            settingsRepository.update { settings ->
                val playbackSettings = if (passthrough) {
                    settings.playback.copy(audioDelayPassthroughMs = value)
                } else {
                    settings.playback.copy(audioDelayPcmMs = value)
                }
                settings.copy(playback = playbackSettings)
            }
        }
    }

    fun setSleepTimer(minutes: Int) {
        sleepTimer.set(minutes)
        showNotice(
            if (minutes > 0) UiText.res(R.string.sleep_app_set, minutes) else UiText.res(R.string.sleep_app_off),
        )
    }

    fun zapRecent(channel: LastChannel) {
        _ui.update { it.copy(quickBar = false) }
        ownSession.start(BouquetEntry(channel.name, channel.reference))
    }

    /** The Guide tile: back to the dashboard, whose Stream page is the programme grid, on this channel. */
    fun openGuideFromBar() {
        _ui.update { it.copy(quickBar = false) }
        onLeaveToDashboard?.invoke()
    }

    /** Quality tile: a fixed quality for this session, or null for the receiver's setting. */
    fun chooseQuality(level: StreamLevel?) {
        _ui.update { it.copy(quickBar = false) }
        quality.setManual(level)
    }

    fun restartStream() {
        _ui.update { it.copy(quickBar = false) }
        showNotice(UiText.res(R.string.quick_restarting))
        if (_ui.value.ownTuner) ownSession.retry() else playback.restart()
    }

    /** Restart tile: a recording goes back to the start, a channel opens again. */
    fun restartFromBar() {
        if (playback.isFilePlayback()) {
            _ui.update { it.copy(quickBar = false) }
            playback.seekFile(0)
            touchFileBar()
        } else {
            restartStream()
        }
    }

    fun player() = playback.exoPlayer()

    /** Whether the current audio goes out undecoded over HDMI; null before the first track. */
    val audioPassthroughActive get() = playback.audioPassthroughActive

    fun dismissExitDialog() {
        _ui.update { it.copy(showExitDialog = false) }
    }

    private fun publishRunningRecordings() {
        val running = recordings.items.value
            .forReceiver(settingsSnapshot.activeReceiver()?.id.orEmpty())
            .filter { it.status == com.enigmacast.domain.recording.RecordingStatus.RECORDING }
        _ui.update {
            it.copy(
                recordingTitle = running.firstOrNull()?.title.orEmpty(),
                recordingChannel = running.joinToString(" · ") { item -> item.channelName.ifBlank { item.title } },
                recordingReferences = running.map { item -> item.reference }.toSet(),
            )
        }
    }

    private fun restart(settings: AppSettings) {
        session?.cancel()
        val receiver = settings.activeReceiver()
        ownSession.bind(receiver?.id)
        if (receiver == null) {
            _ui.update { it.copy(connectionState = ConnectionState.NotConfigured) }
            return
        }
        if (!StreamModePolicy.mirrorsReceiverOsd(settings.streamMode)) {
            startOwnTuner(receiver)
            return
        }
        nowEpgJob?.cancel()
        _ui.update {
            it.copy(
                ownTuner = false,
                banner = false,
                details = false,
                channelList = false,
                bouquetPanel = false,
                bouquets = emptyList(),
                epgChannel = null,
            )
        }
        val osd = OsdController(
            source = osdSource,
            analyzer = analyzer,
            configProvider = { settingsSnapshot.activeReceiver() },
            osdConfigProvider = { settings.osd.toOsdConfig() },
            formatProvider = {
                if (settingsSnapshot.osd.format.equals("jpg", true)) GrabFormat.JPG else GrabFormat.PNG
            },
        )
        val monitor = ServiceMonitor(
            source = serviceSource,
            configProvider = { settingsSnapshot.activeReceiver() },
        )
        val dispatcher = RemoteKeyDispatcher(
            remote = remoteSource,
            scope = viewModelScope,
            configProvider = { settingsSnapshot.activeReceiver() },
            onSent = { key ->
                lastKey = key.name
                osd.onRemoteInput()
                monitor.onRemoteCommand()
            },
        )
        val coordinator = ConnectionCoordinator(
            probe = { infoSource.capabilities(settingsSnapshot.activeReceiver() ?: receiver) },
            heartbeat = { infoSource.powerState(settingsSnapshot.activeReceiver() ?: receiver) },
        )
        osdController = osd
        serviceMonitor = monitor
        keys = dispatcher
        connection = coordinator
        session = viewModelScope.launch {
            launch {
                coordinator.state.collect { state ->
                    _ui.update { it.copy(connectionState = state) }
                    refreshDebug()
                }
            }
            launch {
                monitor.service.collect { service ->
                    _ui.update { it.copy(currentService = service) }
                    refreshPlaybackOptions()
                    refreshDebug()
                }
            }
            launch {
                monitor.changes.collect { service ->
                    if (playback.isFilePlayback()) return@collect
                    val active = settingsSnapshot.activeReceiver() ?: return@collect
                    val target = streamTargets.target(active, service.reference)
                    playback.play(target.url, authorization = target.authorization, internet = target.internet)
                }
            }
            launch {
                osd.update.collect { update ->
                    if (update == null) return@collect
                    val previous = _ui.value.osdFrame?.bitmap
                    val source = analyzer.bitmapFor(update.hash)
                    val frame = if (update.isEmpty || source == null) {
                        null
                    } else {
                        val display = source.copy(Bitmap.Config.ARGB_8888, false)
                        if (display == null) {
                            null
                        } else {
                            OsdFrame(display, update.hash, update.width, update.height, update.durationMs)
                        }
                    }
                    analyzer.markPublished(source)
                    _ui.update { it.copy(osdFrame = frame, osdState = update.state) }
                    if (frame == null) {
                        EnigmaLog.osd("overlay hidden")
                    } else {
                        EnigmaLog.osd("overlay showing ${frame.width}x${frame.height}")
                    }
                    if (previous != null && previous !== frame?.bitmap && !previous.isRecycled) {
                        previous.recycle()
                    }
                    refreshDebug()
                }
            }
            launch {
                osd.state.collect { state ->
                    _ui.update { it.copy(osdState = state) }
                    refreshDebug()
                }
            }
            launch {
                playback.state.collect { playerState ->
                    _ui.update { it.copy(playerState = playerState) }
                    refreshDebug()
                }
            }
            coordinator.start(this)
            monitor.start(this)
            osd.start(this)
        }
    }

    /**
     * Own tuner: no receiver OSD, no key forwarding, no following the receiver's channel.
     * Only the receiver's reachability is watched, with the same read-only heartbeat.
     */
    private fun startOwnTuner(receiver: com.enigmacast.domain.model.ReceiverConfig) {
        osdController = null
        serviceMonitor = null
        keys = null
        val previousFrame = _ui.value.osdFrame?.bitmap
        _ui.update { it.copy(ownTuner = true, osdFrame = null, osdState = OsdPollState.IDLE) }
        if (previousFrame != null && !previousFrame.isRecycled) previousFrame.recycle()
        val coordinator = ConnectionCoordinator(
            probe = { infoSource.capabilities(settingsSnapshot.activeReceiver() ?: receiver) },
            heartbeat = { infoSource.powerState(settingsSnapshot.activeReceiver() ?: receiver) },
        )
        connection = coordinator
        ownSession.ensureStarted()
        session = viewModelScope.launch {
            launch {
                coordinator.state.collect { state ->
                    _ui.update { it.copy(connectionState = state) }
                    refreshDebug()
                }
            }
            launch {
                playback.state.collect { playerState ->
                    _ui.update { it.copy(playerState = playerState) }
                    refreshDebug()
                }
            }
            launch {
                var lastZap = -1
                ownSession.state.collect { own ->
                    val zapped = own.zapCount != lastZap
                    lastZap = own.zapCount
                    _ui.update {
                        it.copy(
                            own = own,
                            currentService = own.channel?.let { channel -> EnigmaService(channel.name, channel.reference) },
                        )
                    }
                    if (zapped && own.channel != null) showBanner(fromZap = true)
                    if (zapped) refreshPlaybackOptions()
                    refreshDebug()
                }
            }
            coordinator.start(this)
        }
    }

    /** Channel logo for the banner and list; null when the receiver has none. */
    suspend fun picon(entry: BouquetEntry): ImageBitmap? {
        val receiver = settingsSnapshot.activeReceiver() ?: return null
        return picons.load(receiver, entry)
    }

    fun cachedPicon(entry: BouquetEntry): ImageBitmap? = picons.cached(entry)

    fun numberOf(entry: BouquetEntry): Int = ownSession.numberOf(entry)

    fun zapTo(entry: BouquetEntry) {
        closeChannelList()
        ownSession.start(entry)
    }

    private fun listPane(ui: RemoteDisplayUiState = _ui.value): ListPane = when {
        !ui.channelList -> ListPane.CLOSED
        ui.bouquetPanel -> ListPane.BOUQUETS
        ui.epgChannel != null -> ListPane.EPG
        else -> ListPane.CHANNELS
    }

    private fun applyPane(pane: ListPane, focusList: Boolean = false) {
        _ui.update {
            it.copy(
                channelList = pane != ListPane.CLOSED,
                bouquetPanel = pane == ListPane.BOUQUETS,
                epgChannel = if (pane == ListPane.EPG) it.epgChannel else null,
                listFocusToken = if (focusList) it.listFocusToken + 1 else it.listFocusToken,
            )
        }
        if (pane == ListPane.CLOSED) nowEpgJob?.cancel()
        if (pane != ListPane.EPG) epgJob?.cancel()
    }

    private fun openChannelList() {
        hideBanner()
        _ui.update { it.copy(listFocusReference = "") }
        applyPane(ChannelListNavigation.open(), focusList = true)
        watchNowEpg()
        loadBouquets()
    }

    private fun closeChannelList() = applyPane(ListPane.CLOSED)

    private fun openBouquets() {
        loadBouquets()
        applyPane(ChannelListNavigation.left(listPane()))
    }

    /** OK or Right on a bouquet: show its channels with what is on now, back in the channel list. */
    fun chooseBouquet(bouquet: BouquetEntry) {
        _ui.update { it.copy(listFocusReference = "") }
        applyPane(ChannelListNavigation.bouquetChosen(), focusList = true)
        bouquetJob?.cancel()
        bouquetJob = viewModelScope.launch {
            if (!ownSession.selectBouquet(bouquet)) {
                showNotice(UiText.res(R.string.notice_bouquet_failed, bouquet.name))
                return@launch
            }
            _ui.update { it.copy(nowEvents = emptyMap(), listFocusToken = it.listFocusToken + 1) }
            watchNowEpg()
        }
    }

    /** Right on a channel in the list: that channel's programmes in a panel right of the list. */
    fun openChannelEpg(entry: BouquetEntry) {
        if (listPane() != ListPane.CHANNELS) return
        val receiver = settingsSnapshot.activeReceiver() ?: return
        _ui.update { it.copy(epgChannel = entry, epgEvents = emptyList(), epgLoading = true, listFocusReference = entry.reference) }
        applyPane(ChannelListNavigation.right(ListPane.CHANNELS))
        epgJob?.cancel()
        epgJob = viewModelScope.launch {
            val now = nowSec()
            val events = guideRepository.serviceEvents(receiver, entry.reference).filter { it.endEpochSec > now }
            _ui.update {
                if (BouquetChannels.sameService(it.epgChannel?.reference.orEmpty(), entry.reference)) {
                    it.copy(epgEvents = events, epgLoading = false)
                } else {
                    it
                }
            }
        }
    }

    /** Left or Back in the programme panel: back to the list, on the same channel. */
    private fun closeChannelEpg() = applyPane(ChannelListNavigation.left(ListPane.EPG), focusList = true)

    /** OK on a programme: watch the channel when it is on now, else offer AutoSwitch or Record. */
    fun chooseProgramme(channel: BouquetEntry, event: GuideEvent) {
        if (event.beginEpochSec <= nowSec()) {
            watchFromGuide(channel)
            return
        }
        val prompt = ProgrammePrompt(event, channel.name, reminders.isScheduled(event.programmeKey))
        _ui.update { it.copy(programmePrompt = prompt) }
    }

    private fun watchFromGuide(channel: BouquetEntry) {
        zapTo(channel)
    }

    /** AutoSwitch in the programme dialog: set it, or cancel it when it was set. */
    fun toggleAutoSwitch() {
        val prompt = _ui.value.programmePrompt ?: return
        _ui.update { it.copy(programmePrompt = null) }
        val receiverId = settingsSnapshot.activeReceiver()?.id.orEmpty()
        showNotice(AutoSwitchTexts.result(reminders.toggle(prompt.event, prompt.channelName, receiverId), prompt), NOTICE_LONG_MS)
    }

    /** Record in the programme dialog: confirm the destination, then store it. */
    fun chooseRecord() {
        val prompt = _ui.value.programmePrompt ?: return
        _ui.update { it.copy(programmePrompt = null) }
        viewModelScope.launch {
            when (val result = recordings.prepare(prompt.event, prompt.channelName)) {
                is com.enigmacast.live.RecordingBook.PrepareResult.Ask -> _ui.update { it.copy(recordAsk = result.ask) }
                is com.enigmacast.live.RecordingBook.PrepareResult.Message -> showNotice(result.text, NOTICE_LONG_MS)
            }
        }
    }

    fun confirmRecord(place: com.enigmacast.domain.model.RecordingTarget, all: Boolean = false) {
        val ask = _ui.value.recordAsk ?: return
        _ui.update { it.copy(recordAsk = null) }
        viewModelScope.launch { showNotice(recordings.schedule(ask.copy(place = place, allUpcoming = all)), NOTICE_LONG_MS) }
    }

    fun dismissRecord() {
        _ui.update { it.copy(recordAsk = null) }
    }

    /** Record tile: stop a capture that is running, otherwise record the programme on now. */
    fun recordWatching() {
        val running = recordings.items.value.filter { it.status == com.enigmacast.domain.recording.RecordingStatus.RECORDING }
        if (running.isNotEmpty()) {
            closeQuickBar()
            viewModelScope.launch {
                running.forEach { recordings.stop(it.id) }
                showNotice(UiText.res(R.string.record_stopped), NOTICE_LONG_MS)
            }
            return
        }
        closeQuickBar()
        viewModelScope.launch {
            val ui = _ui.value
            val reference = ui.own.channel?.reference?.takeIf { it.isNotBlank() }
                ?: ui.currentService?.reference.orEmpty()
            val channelName = ui.own.channel?.name?.takeIf { it.isNotBlank() }
                ?: ui.currentService?.name.orEmpty()
            if (reference.isBlank()) {
                showNotice(UiText.res(R.string.record_no_programme), NOTICE_LONG_MS)
                return@launch
            }
            val now = nowSec()
            val event = airingFromBanner(ui, reference, now)
                ?: airingFromNow(ui, reference, now)
                ?: airingFromGuide(reference, now)
            if (event == null) {
                showNotice(UiText.res(R.string.record_no_programme), NOTICE_LONG_MS)
                return@launch
            }
            when (val result = recordings.prepare(event, channelName.ifBlank { event.title })) {
                is com.enigmacast.live.RecordingBook.PrepareResult.Ask -> _ui.update { it.copy(recordAsk = result.ask) }
                is com.enigmacast.live.RecordingBook.PrepareResult.Message -> showNotice(result.text, NOTICE_LONG_MS)
            }
        }
    }

    private fun airingFromBanner(ui: RemoteDisplayUiState, reference: String, now: Long): GuideEvent? {
        val current = ui.own.guide?.currentEvent ?: return null
        if (current.eventId < 0 || current.beginEpochSec > now || current.beginEpochSec + current.durationSec <= now) return null
        return GuideEvent(
            eventId = current.eventId,
            reference = reference,
            title = current.title,
            shortDescription = current.shortDescription,
            plot = current.plot,
            beginEpochSec = current.beginEpochSec,
            durationSec = current.durationSec,
        )
    }

    private fun airingFromNow(ui: RemoteDisplayUiState, reference: String, now: Long): GuideEvent? {
        val current = NowEvents.lookup(ui.nowEvents, reference, now) ?: return null
        if (current.eventId < 0) return null
        return GuideEvent(
            eventId = current.eventId,
            reference = current.reference.ifBlank { reference },
            title = current.title,
            shortDescription = "",
            plot = "",
            beginEpochSec = current.beginEpochSec,
            durationSec = current.durationSec,
        )
    }

    private suspend fun airingFromGuide(reference: String, now: Long): GuideEvent? {
        val receiver = settingsSnapshot.activeReceiver() ?: return null
        return runCatching { guideRepository.serviceEvents(receiver, reference) }
            .getOrDefault(emptyList())
            .firstOrNull { it.isAiring(now) }
    }

    fun dismissProgramme() {
        _ui.update { it.copy(programmePrompt = null) }
    }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    private fun loadBouquets() {
        val radio = BouquetChannels.isRadioBouquet(ownSession.state.value.bouquetReference)
        val cached = _ui.value.bouquets
        if (cached.isNotEmpty() && cached.any { BouquetChannels.isRadioBouquet(it.reference) } == radio) return
        viewModelScope.launch {
            val receiver = settingsSnapshot.activeReceiver() ?: return@launch
            val loaded = runCatching {
                if (radio) serviceSource.radioBouquets(receiver) else serviceSource.televisionBouquets(receiver)
            }
            loaded.onSuccess { list -> _ui.update { it.copy(bouquets = list) } }
        }
    }

    /** Keeps "now on air" for the list's bouquet current while the list is open. */
    private fun watchNowEpg() {
        nowEpgJob?.cancel()
        nowEpgJob = viewModelScope.launch {
            while (true) {
                val receiver = settingsSnapshot.activeReceiver() ?: return@launch
                val bouquet = ownSession.state.value.bouquetReference
                val cached = nowEpg.cached(receiver, bouquet)
                if (cached.isNotEmpty()) _ui.update { it.copy(nowEvents = cached) }
                val events = nowEpg.load(receiver, bouquet)
                _ui.update { it.copy(nowEvents = events) }
                delay(NOW_EPG_CHECK_MS)
            }
        }
    }

    private fun playingRadio(ui: RemoteDisplayUiState = _ui.value): Boolean =
        ui.ownTuner && !ui.playerState.filePlayback && ui.own.channel != null &&
            BouquetChannels.isRadioBouquet(ui.own.bouquetReference)

    private fun showBanner(details: Boolean = _ui.value.details, fromZap: Boolean = false) {
        if (playingRadio()) return
        ownSession.refreshGuideIfStale()
        _ui.update { it.copy(banner = true, details = details, bannerFromZap = fromZap) }
        watchOsdInfo()
        bannerJob?.cancel()
        // The quick bar keeps the banner up while it has the focus.
        if (_ui.value.quickBar) return
        bannerJob = viewModelScope.launch {
            delay(if (details) DETAILS_VISIBLE_MS else BANNER_VISIBLE_MS)
            _ui.update { it.copy(banner = false, details = false) }
            watchOsdInfo()
        }
    }

    private fun hideBanner() {
        bannerJob?.cancel()
        _ui.update { it.copy(banner = false, details = false, quickBar = false) }
        watchOsdInfo()
    }

    /**
     * Polls receiver signal and service data only while the channel banner or quick bar is
     * visible, and folds it with the stream's own TS facts into [OsdInfo].
     */
    private fun watchOsdInfo() {
        val ui = _ui.value
        val visible = ui.ownTuner && (ui.banner || ui.quickBar)
        val reference = ui.currentService?.reference.orEmpty()
        val receiver = settingsSnapshot.activeReceiver()
        if (!visible || reference.isBlank() || receiver == null) {
            osdInfoJob?.cancel()
            osdInfoJob = null
            return
        }
        val key = "${receiver.id}|$reference"
        if (osdInfoJob?.isActive == true && osdInfoKey == key) return
        osdInfoJob?.cancel()
        if (osdInfoKey != key) _ui.update { it.copy(osdInfo = OsdInfo.Empty) }
        osdInfoKey = key
        val internet = ServiceStreams.internetUrl(reference) != null || ServiceStreams.isStreamPlayer(reference)
        osdInfoJob = viewModelScope.launch {
            combine(osdInfoRepository.watch(receiver, reference), playback.streamFacts, playback.state) { data, stream, player ->
                OsdInfoBuilder.build(
                    OsdInfoInput(
                        reference = reference,
                        internet = internet,
                        followsReceiver = false,
                        receiver = data,
                        stream = stream,
                        player = PlayerFacts(
                            width = player.videoWidth,
                            height = player.videoHeight,
                            pixelRatio = player.pixelRatio,
                            audioMime = player.audioCodec,
                            audioChannels = player.audioChannels,
                            subtitleTracks = player.subtitleTrackCount,
                        ),
                    ),
                )
            }.collect { info ->
                _ui.update { it.copy(osdInfo = info) }
            }
        }
    }

    private fun showNotice(text: UiText, durationMs: Long = NOTICE_MS) {
        _ui.update { it.copy(notice = text) }
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(durationMs)
            _ui.update { it.copy(notice = UiText.Empty) }
        }
    }

    private fun handleOwnKey(event: KeyEvent): Boolean {
        val action = OwnTunerKeys.action(event.keyCode)
        val ui = _ui.value
        lastKey = KeyEvent.keyCodeToString(event.keyCode)
        if (action == OwnKeyAction.BACK) {
            if (event.action == KeyEvent.ACTION_UP) ownBack()
            return true
        }
        // The quick bar is plain Compose focus (Left/Right between tiles, OK opens a picker).
        if (ui.quickBar) return false
        // Down on a banner that OK, Info or Right brought up opens the quick bar;
        // after a zap Down keeps zapping.
        val radio = playingRadio(ui)
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && !ui.channelList && (radio || (ui.banner && !ui.bannerFromZap))) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) openQuickBar()
            return true
        }
        if (ui.channelList) {
            // Left in the channel list reveals the bouquets, Left in a channel's programme panel
            // goes back to that channel; everything else is plain Compose focus and clicks in the
            // list (Right on a channel or bouquet is handled there).
            if (!ui.bouquetPanel && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    if (ui.epgChannel != null) closeChannelEpg() else openBouquets()
                }
                return true
            }
            return false
        }
        if (action == OwnKeyAction.PASS) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        when (action) {
            OwnKeyAction.NEXT, OwnKeyAction.PREVIOUS -> if (down && event.repeatCount % 4 == 0) {
                ownSession.zapRelative(if (action == OwnKeyAction.NEXT) 1 else -1)
            }
            OwnKeyAction.OK -> if (!down) {
                val tuner = ui.own.tuner.status
                when {
                    tuner == TunerStatus.FULL || tuner == TunerStatus.PROBLEM -> ownSession.retry()
                    // Banner up, or radio (no banner): pause or resume. Left opens the list.
                    // An internet stream cannot pause, so OK still opens the list there.
                    radio || ui.banner -> if (!playback.toggleTimeshiftPause()) openChannelList()
                    else -> showBanner()
                }
            }
            OwnKeyAction.LIST -> if (!down) openChannelList()
            OwnKeyAction.DETAILS -> if (!down) {
                if (ui.banner && ui.details) hideBanner() else showBanner(details = true)
            }
            OwnKeyAction.BANNER -> if (down && event.repeatCount == 0) showBanner()
            OwnKeyAction.DIGIT -> if (down && event.repeatCount == 0) pushDigit(OwnTunerKeys.digit(event.keyCode))
            else -> Unit
        }
        return true
    }

    private fun ownBack() {
        val ui = _ui.value
        when {
            ui.programmePrompt != null -> dismissProgramme()
            ui.searchOpen -> closeSearch()
            ui.quickBar -> closeQuickBar()
            ui.channelList -> {
                val pane = ChannelListNavigation.back(listPane(ui))
                applyPane(pane, focusList = pane == ListPane.CHANNELS)
            }
            ui.numberEntry.isNotEmpty() -> {
                numberJob?.cancel()
                numberEntry.clear()
                _ui.update { it.copy(numberEntry = "") }
            }
            ui.banner && !playingRadio(ui) -> hideBanner()
            // Back on the bare picture returns to the dashboard (the programme grid, on this channel).
            else -> onLeaveToDashboard?.invoke()
        }
    }

    private fun pushDigit(digit: Int) {
        val complete = numberEntry.push(digit)
        _ui.update { it.copy(numberEntry = numberEntry.digits) }
        numberJob?.cancel()
        if (complete) {
            applyNumber()
            return
        }
        numberJob = viewModelScope.launch {
            delay(NUMBER_ENTRY_MS)
            applyNumber()
        }
    }

    private fun applyNumber() {
        val number = numberEntry.take() ?: return
        _ui.update { it.copy(numberEntry = "") }
        if (!ownSession.zapNumber(number)) showNotice(UiText.res(R.string.notice_number_not_in_list, number))
    }

    private fun handleFileKey(event: KeyEvent): Boolean {
        val ui = _ui.value
        if (ui.quickBar) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) closeQuickBar()
            return event.keyCode == KeyEvent.KEYCODE_BACK
        }
        if (ui.fileBar) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) hideFileBar()
            return event.keyCode == KeyEvent.KEYCODE_BACK
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.action == KeyEvent.ACTION_UP) showFileBar()
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (event.action == KeyEvent.ACTION_UP) playback.toggleFilePause()
            }
            KeyEvent.KEYCODE_BACK -> if (event.action == KeyEvent.ACTION_UP) leaveRecording()
        }
        return true
    }

    /** Back from a recording: stop the file and return to that row in My Items. */
    private fun leaveRecording() {
        val id = recordings.nowPlaying.value?.id
        playback.stopFile()
        recordings.clearNowPlaying()
        hideFileBar()
        if (_ui.value.ownTuner) ownSession.resumeAfterFile() else resumeFollow()
        if (id != null) onLeaveToMyItems?.invoke(id) else onLeaveToDashboard?.invoke()
    }

    private fun resumeFollow() {
        val reference = _ui.value.currentService?.reference?.takeIf { it.isNotBlank() } ?: return
        val receiver = settingsSnapshot.activeReceiver() ?: return
        viewModelScope.launch {
            val target = streamTargets.target(receiver, reference)
            playback.play(target.url, force = true, authorization = target.authorization, internet = target.internet)
        }
    }

    private fun handleKey(event: KeyEvent): Boolean {
        if (playback.isFilePlayback()) return handleFileKey(event)
        if (_ui.value.ownTuner) return handleOwnKey(event)
        if (_ui.value.searchOpen) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) closeSearch()
                return true
            }
            return false
        }
        if (_ui.value.quickBar) {
            // Following the receiver: the bar keeps the keys while it is open; Back closes it.
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) closeQuickBar()
                return true
            }
            return false
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            releaseBack()
            return true
        }
        if (_ui.value.showExitDialog) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dismissExitDialog()
                return true
            }
            return false
        }
        val mapped = EnigmaKeyMapper.fromAndroidKeyCode(event.keyCode) ?: return false
        if (mapped == EnigmaKey.EXIT) return handleBack(event)
        if (mapped == EnigmaKey.OK) return handleOk(event)
        if (mapped == EnigmaKey.BLUE) return handleBlue(event)
        if (mapped == EnigmaKey.INFO) return handleInfo(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) keys?.onDown(mapped)
            KeyEvent.ACTION_UP -> keys?.onUp(mapped)
        }
        return true
    }

    private fun handleBack(event: KeyEvent): Boolean {
        val threshold = settingsSnapshot.remote.longPressMs
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount != 0) return true
                backPressToken += 1
                val token = backPressToken
                backDownAt = SystemClock.elapsedRealtime()
                dashboardLeaveSent = false
                backJob?.cancel()
                backJob = viewModelScope.launch {
                    delay(threshold)
                    if (token != backPressToken || token == releasedBackToken || dashboardLeaveSent) return@launch
                    dashboardLeaveSent = true
                    onLeaveToDashboard?.invoke()
                }
            }
            KeyEvent.ACTION_UP -> releaseBack()
        }
        return true
    }

    private fun releaseBack() {
        backJob?.cancel()
        val token = backPressToken
        if (token == 0 || token == releasedBackToken) return
        releasedBackToken = token
        val shortPress = SystemClock.elapsedRealtime() - backDownAt < settingsSnapshot.remote.longPressMs
        if (!dashboardLeaveSent && shortPress) {
            keys?.onDown(EnigmaKey.EXIT)
            keys?.onUp(EnigmaKey.EXIT)
        }
        dashboardLeaveSent = false
    }

    private fun handleOk(event: KeyEvent): Boolean {
        val threshold = settingsSnapshot.remote.longPressMs
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount != 0) return true
                okMenuSent = false
                okJob?.cancel()
                okJob = viewModelScope.launch {
                    delay(threshold)
                    okMenuSent = true
                    keys?.onDown(EnigmaKey.MENU)
                    keys?.onUp(EnigmaKey.MENU)
                }
            }
            KeyEvent.ACTION_UP -> {
                okJob?.cancel()
                if (!okMenuSent) {
                    keys?.onDown(EnigmaKey.OK)
                    keys?.onUp(EnigmaKey.OK)
                }
                okMenuSent = false
            }
        }
        return true
    }

    private fun handleBlue(event: KeyEvent): Boolean {
        val threshold = settingsSnapshot.remote.longPressMs
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount != 0) return true
                blueTextSent = false
                blueJob?.cancel()
                blueJob = viewModelScope.launch {
                    delay(threshold)
                    blueTextSent = true
                    keys?.onDown(EnigmaKey.TEXT)
                    keys?.onUp(EnigmaKey.TEXT)
                }
            }
            KeyEvent.ACTION_UP -> {
                blueJob?.cancel()
                if (!blueTextSent) {
                    keys?.onDown(EnigmaKey.BLUE)
                    keys?.onUp(EnigmaKey.BLUE)
                }
                blueTextSent = false
            }
        }
        return true
    }

    /** Following the receiver: Info goes to the receiver, holding Info opens the app's quick bar. */
    private fun handleInfo(event: KeyEvent): Boolean {
        val threshold = settingsSnapshot.remote.longPressMs
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount != 0) return true
                infoQuickBar = false
                infoJob?.cancel()
                infoJob = viewModelScope.launch {
                    delay(threshold)
                    infoQuickBar = true
                    openQuickBar()
                }
            }
            KeyEvent.ACTION_UP -> {
                infoJob?.cancel()
                if (!infoQuickBar) {
                    keys?.onDown(EnigmaKey.INFO)
                    keys?.onUp(EnigmaKey.INFO)
                }
                infoQuickBar = false
            }
        }
        return true
    }

    private fun refreshDebug() {
        val ui = _ui.value
        val player = ui.playerState
        val host = settingsSnapshot.activeReceiver()?.host.orEmpty()
        val latency = when {
            player.liveOffsetMs >= 0 -> "${player.liveOffsetMs} ms"
            else -> "${player.bufferMs} ms buffer"
        }
        _ui.update {
            it.copy(
                debugInfo = DebugInfo(
                    host = host,
                    serviceName = ui.currentService?.name.orEmpty(),
                    serviceReference = ui.currentService?.reference.orEmpty(),
                    video = listOf(
                        "${player.videoWidth}x${player.videoHeight}",
                        if (player.frameRate > 0f) "${player.frameRate.toInt()} fps" else "",
                        player.videoCodec,
                    ).filter { part -> part.isNotBlank() }.joinToString(" "),
                    audio = player.audioCodec,
                    media3State = player.playbackState,
                    latency = latency,
                    osdState = ui.osdState.name,
                    osdRequestLatencyMs = ui.osdFrame?.downloadDurationMs ?: 0,
                    osdFps = osdController?.framesPerSecond?.value ?: 0f,
                    lastKey = lastKey,
                    network = when (val state = ui.connectionState) {
                        is ConnectionState.Connected -> "online"
                        is ConnectionState.Reconnecting -> state.reason
                        is ConnectionState.Failed -> state.reason
                        ConnectionState.Connecting -> "connecting"
                        ConnectionState.Idle -> "idle"
                        ConnectionState.NotConfigured -> "not configured"
                    },
                ),
            )
        }
    }

    private fun registerNetwork() {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connection?.nudge()
                playback.reconnect()
            }
        }
        networkCallback = callback
        runCatching {
            manager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback,
            )
        }
    }

    override fun onCleared() {
        inputBus.handler = null
        session?.cancel()
        playback.release()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        networkCallback?.let { callback ->
            runCatching { manager?.unregisterNetworkCallback(callback) }
        }
        super.onCleared()
    }
}

private const val BANNER_VISIBLE_MS = 5_000L
private const val DETAILS_VISIBLE_MS = 12_000L
private const val NUMBER_ENTRY_MS = 1_500L
private const val NOW_EPG_CHECK_MS = 30_000L
private const val NOTICE_MS = 2_500L
private const val NOTICE_LONG_MS = 8_000L
private const val GUIDE_PAGE_ROWS = 8

private fun com.enigmacast.domain.model.OsdSettings.toOsdConfig(): OsdConfig {
    return OsdConfig(
        unchangedToSlowMs = unchangedToSlowMs,
        unchangedToIdleMs = hideTimeoutMs,
        slowPollMs = slowPollMs,
        idlePollMs = idlePollMs,
        hiddenPollMs = idlePollMs,
        fastPolling = fastPolling,
    )
}
