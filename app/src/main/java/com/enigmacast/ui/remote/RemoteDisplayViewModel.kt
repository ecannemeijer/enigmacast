package com.enigmacast.ui.remote

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.view.KeyEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.text.Cue
import com.enigmacast.connection.ConnectionCoordinator
import com.enigmacast.data.openwebif.OpenWebifEndpoints
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.model.AppSettings
import com.enigmacast.domain.model.ConnectionState
import com.enigmacast.domain.model.DebugInfo
import com.enigmacast.domain.model.EnigmaService
import com.enigmacast.domain.model.PlayerState
import com.enigmacast.domain.osd.GrabFormat
import com.enigmacast.domain.osd.OsdConfig
import com.enigmacast.domain.osd.OsdController
import com.enigmacast.domain.osd.OsdPollState
import com.enigmacast.domain.remote.EnigmaKey
import com.enigmacast.domain.remote.EnigmaKeyMapper
import com.enigmacast.domain.service.ServiceMonitor
import com.enigmacast.domain.source.OsdSource
import com.enigmacast.domain.source.ReceiverInfoSource
import com.enigmacast.domain.source.RemoteControlSource
import com.enigmacast.domain.source.ServiceStateSource
import com.enigmacast.osd.AndroidOsdAnalyzer
import com.enigmacast.playback.LivePlaybackController
import com.enigmacast.remote.RemoteInputBus
import com.enigmacast.remote.RemoteKeyDispatcher
import com.enigmacast.util.DebugFlags
import com.enigmacast.util.EnigmaLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
) : ViewModel() {
    private val _ui = MutableStateFlow(RemoteDisplayUiState())
    val uiState: StateFlow<RemoteDisplayUiState> = _ui.asStateFlow()
    private val analyzer = AndroidOsdAnalyzer()
    private var settingsSnapshot = AppSettings()
    private var sessionSignature: String? = null
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
    var onLeaveToDashboard: (() -> Unit)? = null
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
                    )
                }
                val signature = listOf(
                    settings.activeReceiver(),
                    settings.osd.copy(debugBorders = false),
                    settings.remote,
                ).toString()
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
    }

    fun player() = playback.exoPlayer()

    fun dismissExitDialog() {
        _ui.update { it.copy(showExitDialog = false) }
    }

    private fun restart(settings: AppSettings) {
        session?.cancel()
        val receiver = settings.activeReceiver()
        if (receiver == null) {
            _ui.update { it.copy(connectionState = ConnectionState.Failed("No receiver configured")) }
            return
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
                    refreshDebug()
                }
            }
            launch {
                monitor.changes.collect { service ->
                    val active = settingsSnapshot.activeReceiver() ?: return@collect
                    playback.play(endpoints.stream(active, service.reference))
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

    private fun handleKey(event: KeyEvent): Boolean {
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
