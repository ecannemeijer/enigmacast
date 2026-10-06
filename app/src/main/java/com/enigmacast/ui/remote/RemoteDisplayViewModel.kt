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
import com.enigmacast.domain.epg.GuideCursor
import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.epg.GuideTimeline
import com.enigmacast.domain.model.AppSettings
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ConnectionState
import com.enigmacast.domain.model.DebugInfo
import com.enigmacast.domain.model.EnigmaService
import com.enigmacast.domain.model.LastChannel
import com.enigmacast.domain.model.PlayerState
import com.enigmacast.domain.model.StreamMode
import com.enigmacast.domain.network.PlainHttpRisk
import com.enigmacast.domain.network.ReceiverRoute
import com.enigmacast.domain.network.ReceiverRouting
import com.enigmacast.domain.osd.GrabFormat
import com.enigmacast.domain.osd.OsdConfig
import com.enigmacast.domain.osd.OsdController
import com.enigmacast.domain.osd.OsdPollState
import com.enigmacast.domain.player.PictureMode
import com.enigmacast.domain.remote.EnigmaKey
import com.enigmacast.domain.remote.EnigmaKeyMapper
import com.enigmacast.domain.service.BouquetChannels
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
import com.enigmacast.live.OwnTunerSession
import com.enigmacast.live.OwnTunerState
import com.enigmacast.live.PiconRepository
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    /** Channel list: the channel whose programme guide is open right of the list (Right on a channel). */
    val epgChannel: BouquetEntry? = null,
    val epgEvents: List<GuideEvent> = emptyList(),
    val epgLoading: Boolean = false,
    /** The channel the list focuses; blank means the channel that plays. */
    val listFocusReference: String = "",
    /** The full-screen programme guide that Back opens in the player. */
    val guide: Boolean = false,
    val guideCursor: GuideCursor = GuideCursor(),
    /** Programmes per channel (key [NowEvents.key]) of the guide's bouquet, loaded in blocks. */
    val guideEvents: Map<String, List<GuideEvent>> = emptyMap(),
    val guideBouquets: Boolean = false,
    val guideLoading: Boolean = false,
    val guideFailed: Boolean = false,
    val recordPrompt: RecordPrompt? = null,
    val recordBusy: Boolean = false,
    /** Programmes set to record from this app in this session ([GuideEvent.recordKey]). */
    val recordedKeys: Set<String> = emptySet(),
)

/** "Record this programme?" for a programme that has not started yet. */
data class RecordPrompt(val event: GuideEvent, val channelName: String)

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
    private var bannerJob: Job? = null
    private var numberJob: Job? = null
    private var noticeJob: Job? = null
    private var nowEpgJob: Job? = null
    private var bouquetJob: Job? = null
    private var epgJob: Job? = null
    private var guideBouquetJob: Job? = null
    private var guideBouquet = ""
    private val guideLoaded = mutableSetOf<Long>()
    private val guidePending = mutableSetOf<Long>()
    private val numberEntry = NumberEntry()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        playback.attach(viewModelScope)
        inputBus.handler = ::handleKey
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                settingsSnapshot = settings
                DebugFlags.verbose = settings.debug.verboseLogging
                playback.updateSettings(settings.playback)
                _ui.update {
                    it.copy(
                        debugOverlayEnabled = settings.debug.overlay,
                        osdDebugBorders = settings.osd.debugBorders,
                        hdmiPassthrough = settings.playback.hdmiPassthrough,
                        recent = settings.recentChannels,
                        receiverAddress = settings.activeReceiver()?.let { receiver -> "${receiver.host}:${receiver.streamingPort}" }.orEmpty(),
                    )
                }
                refreshPlaybackOptions()
                val signature = listOf(
                    settings.activeReceiver(),
                    settings.osd.copy(debugBorders = false),
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
    }

    /** Back on the bar: close it; the banner (own tuner) stays for its usual few seconds. */
    fun closeQuickBar() {
        if (!_ui.value.quickBar) return
        _ui.update { it.copy(quickBar = false) }
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

    /** The Guide tile: the full-screen programme guide, like Back. */
    fun openGuideFromBar() {
        _ui.update { it.copy(quickBar = false) }
        openGuide()
    }

    fun restartStream() {
        _ui.update { it.copy(quickBar = false) }
        showNotice(UiText.res(R.string.quick_restarting))
        if (_ui.value.ownTuner) ownSession.retry() else playback.restart()
    }

    fun player() = playback.exoPlayer()

    /** Whether the current audio goes out undecoded over HDMI; null before the first track. */
    val audioPassthroughActive get() = playback.audioPassthroughActive

    fun dismissExitDialog() {
        _ui.update { it.copy(showExitDialog = false) }
    }

    private fun restart(settings: AppSettings) {
        session?.cancel()
        val receiver = settings.activeReceiver()
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
                guide = false,
                guideBouquets = false,
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
                    val active = settingsSnapshot.activeReceiver() ?: return@collect
                    val target = streamTargets.target(active, service.reference)
                    playback.play(target.url, authorization = target.authorization)
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

    /** OK on a programme: watch the channel when it is on now, else offer to record it. */
    fun chooseProgramme(channel: BouquetEntry, event: GuideEvent) {
        if (event.beginEpochSec <= nowSec()) {
            watchFromGuide(channel)
            return
        }
        if (event.eventId < 0) {
            showNotice(UiText.res(R.string.epg_record_unavailable))
            return
        }
        _ui.update { it.copy(recordPrompt = RecordPrompt(event, channel.name)) }
    }

    private fun watchFromGuide(channel: BouquetEntry) {
        if (_ui.value.guide) closeGuide(toDashboard = false)
        zapTo(channel)
    }

    /** Asks the receiver to record the programme in [RemoteDisplayUiState.recordPrompt]. */
    fun confirmRecording() {
        val prompt = _ui.value.recordPrompt ?: return
        val receiver = settingsSnapshot.activeReceiver() ?: return
        if (_ui.value.recordBusy) return
        _ui.update { it.copy(recordBusy = true) }
        viewModelScope.launch {
            val result = try {
                guideRepository.record(receiver, prompt.event)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                EnigmaLog.service("record failed: ${error.javaClass.simpleName}")
                null
            }
            val success = result?.success == true
            _ui.update {
                it.copy(
                    recordPrompt = null,
                    recordBusy = false,
                    recordedKeys = if (success) it.recordedKeys + prompt.event.recordKey else it.recordedKeys,
                )
            }
            showNotice(
                when {
                    success -> UiText.res(R.string.epg_record_set, prompt.event.title)
                    result != null && result.message.isNotBlank() -> UiText.res(R.string.epg_record_refused, result.message)
                    else -> UiText.res(R.string.epg_record_failed)
                },
                NOTICE_LONG_MS,
            )
        }
    }

    fun dismissRecording() {
        if (!_ui.value.recordBusy) _ui.update { it.copy(recordPrompt = null) }
    }

    /**
     * Back in the player: the full-screen guide for the bouquet in use, on the channel that
     * plays and the current half hour.
     */
    private fun openGuide() {
        if (!_ui.value.ownTuner) return
        hideBanner()
        if (_ui.value.channelList) applyPane(ListPane.CLOSED)
        numberJob?.cancel()
        numberEntry.clear()
        val now = nowSec()
        val own = ownSession.state.value
        val row = ChannelListNavigation.channelFocusIndex(own.channels, own.channel?.reference.orEmpty())
        _ui.update {
            it.copy(
                guide = true,
                guideBouquets = false,
                numberEntry = "",
                guideCursor = GuideCursor(row, now, GuideTimeline.slotFloor(now)),
            )
        }
        // Blocks are asked again (the repository answers from its cache while fresh).
        guideLoaded.clear()
        loadBouquets()
        ensureGuideData()
    }

    private fun closeGuide(toDashboard: Boolean) {
        guideBouquetJob?.cancel()
        _ui.update { it.copy(guide = false, guideBouquets = false) }
        if (toDashboard) onLeaveToDashboard?.invoke()
    }

    /** Loads the 3-hour blocks of the guide's bouquet around the visible time window. */
    private fun ensureGuideData() {
        val receiver = settingsSnapshot.activeReceiver() ?: return
        val bouquet = ownSession.state.value.bouquetReference
        if (bouquet.isBlank()) return
        if (bouquet != guideBouquet) {
            guideBouquet = bouquet
            guideLoaded.clear()
            guidePending.clear()
            _ui.update { it.copy(guideEvents = emptyMap(), guideFailed = false) }
        }
        GuideTimeline.chunksFor(_ui.value.guideCursor.windowStartSec).forEach { chunk ->
            if (chunk in guideLoaded || chunk in guidePending) return@forEach
            guidePending += chunk
            viewModelScope.launch {
                val events = guideRepository.bouquetChunk(receiver, bouquet, chunk)
                if (bouquet != guideBouquet) return@launch
                guidePending -= chunk
                if (events == null) {
                    _ui.update { it.copy(guideFailed = true, guideLoading = guidePending.isNotEmpty()) }
                    return@launch
                }
                guideLoaded += chunk
                _ui.update {
                    it.copy(
                        guideEvents = GuideTimeline.merge(it.guideEvents, events),
                        guideFailed = false,
                        guideLoading = guidePending.isNotEmpty(),
                    )
                }
            }
        }
        _ui.update { it.copy(guideLoading = guidePending.isNotEmpty()) }
    }

    /**
     * The guide's keys: Up/Down through channels (CH+/CH- and page keys by a page), Left/Right
     * through programmes, OK to watch or record. Left on the programme on air opens the bouquet
     * column. The bouquet column and the record dialog use plain Compose focus.
     */
    private fun handleGuideKey(event: KeyEvent): Boolean {
        val ui = _ui.value
        if (ui.guideBouquets || ui.recordPrompt != null) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (down) moveGuideRows(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) moveGuideRows(1)
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> if (down) moveGuideRows(-GUIDE_PAGE_ROWS)
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_UP -> if (down) moveGuideRows(GUIDE_PAGE_ROWS)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) moveGuideTime(-1, edgeAllowed = event.repeatCount == 0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) moveGuideTime(1, edgeAllowed = false)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (!down) chooseGuideCell()
            else -> return OwnTunerKeys.action(event.keyCode) != OwnKeyAction.PASS
        }
        return true
    }

    private fun moveGuideRows(delta: Int) {
        val ui = _ui.value
        val rows = ui.own.channels.size
        val cursor = GuideTimeline.clamp(GuideTimeline.moveVertical(ui.guideCursor, delta, rows), nowSec(), rows)
        _ui.update { it.copy(guideCursor = cursor) }
        if (cursor.windowStartSec != ui.guideCursor.windowStartSec) ensureGuideData()
    }

    private fun moveGuideTime(direction: Int, edgeAllowed: Boolean) {
        val ui = _ui.value
        val channels = ui.own.channels
        val entry = channels.getOrNull(ui.guideCursor.row)
        if (entry == null) {
            if (direction < 0 && edgeAllowed) openGuideBouquets()
            return
        }
        val now = nowSec()
        val cursor = GuideTimeline.clamp(ui.guideCursor, now, channels.size)
        val move = GuideTimeline.moveHorizontal(ui.guideEvents[NowEvents.key(entry.reference)].orEmpty(), cursor, direction, now)
        if (move.atLeftEdge) {
            if (edgeAllowed) openGuideBouquets()
            return
        }
        _ui.update { it.copy(guideCursor = move.cursor) }
        if (move.cursor.windowStartSec != ui.guideCursor.windowStartSec) ensureGuideData()
    }

    private fun chooseGuideCell() {
        val ui = _ui.value
        val entry = ui.own.channels.getOrNull(ui.guideCursor.row) ?: return
        val now = nowSec()
        val cell = GuideTimeline.focusedCell(ui.guideEvents[NowEvents.key(entry.reference)].orEmpty(), ui.guideCursor, now) ?: return
        val event = cell.event
        when {
            event != null -> chooseProgramme(entry, event)
            cell.startSec <= now -> watchFromGuide(entry)
        }
    }

    private fun openGuideBouquets() {
        loadBouquets()
        _ui.update { it.copy(guideBouquets = true) }
    }

    /** OK or Right on a bouquet in the guide: that bouquet's channels and programmes. */
    fun chooseGuideBouquet(bouquet: BouquetEntry) {
        _ui.update { it.copy(guideBouquets = false) }
        guideBouquetJob?.cancel()
        guideBouquetJob = viewModelScope.launch {
            if (!ownSession.selectBouquet(bouquet)) {
                showNotice(UiText.res(R.string.notice_bouquet_failed, bouquet.name))
                return@launch
            }
            val own = ownSession.state.value
            val now = nowSec()
            val row = ChannelListNavigation.channelFocusIndex(own.channels, own.channel?.reference.orEmpty())
            _ui.update {
                it.copy(own = own, nowEvents = emptyMap(), guideCursor = GuideCursor(row, now, GuideTimeline.slotFloor(now)))
            }
            ensureGuideData()
        }
    }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    private fun loadBouquets() {
        if (_ui.value.bouquets.isNotEmpty()) return
        viewModelScope.launch {
            val receiver = settingsSnapshot.activeReceiver() ?: return@launch
            runCatching { serviceSource.televisionBouquets(receiver) }
                .onSuccess { list -> _ui.update { it.copy(bouquets = list) } }
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

    private fun showBanner(details: Boolean = _ui.value.details, fromZap: Boolean = false) {
        ownSession.refreshGuideIfStale()
        _ui.update { it.copy(banner = true, details = details, bannerFromZap = fromZap) }
        bannerJob?.cancel()
        // The quick bar keeps the banner up while it has the focus.
        if (_ui.value.quickBar) return
        bannerJob = viewModelScope.launch {
            delay(if (details) DETAILS_VISIBLE_MS else BANNER_VISIBLE_MS)
            _ui.update { it.copy(banner = false, details = false) }
        }
    }

    private fun hideBanner() {
        bannerJob?.cancel()
        _ui.update { it.copy(banner = false, details = false, quickBar = false) }
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
        if (ui.guide) return handleGuideKey(event)
        // The quick bar is plain Compose focus (Left/Right between tiles, OK opens a picker).
        if (ui.quickBar) return false
        // Down on a banner that OK, Info or Left/Right brought up opens the quick bar;
        // after a zap Down keeps zapping.
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && ui.banner && !ui.bannerFromZap && !ui.channelList) {
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
                    ui.banner -> openChannelList()
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
            ui.recordPrompt != null -> dismissRecording()
            ui.quickBar -> closeQuickBar()
            ui.guide -> if (ui.guideBouquets) _ui.update { it.copy(guideBouquets = false) } else closeGuide(toDashboard = true)
            ui.channelList -> {
                val pane = ChannelListNavigation.back(listPane(ui))
                applyPane(pane, focusList = pane == ListPane.CHANNELS)
            }
            ui.numberEntry.isNotEmpty() -> {
                numberJob?.cancel()
                numberEntry.clear()
                _ui.update { it.copy(numberEntry = "") }
            }
            ui.banner -> hideBanner()
            // Back on the bare picture opens the programme guide; Back there leaves to the menu.
            else -> openGuide()
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

    private fun handleKey(event: KeyEvent): Boolean {
        if (_ui.value.ownTuner) return handleOwnKey(event)
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
