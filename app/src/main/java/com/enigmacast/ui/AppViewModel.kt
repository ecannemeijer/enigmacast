package com.enigmacast.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.BuildConfig
import com.enigmacast.R
import com.enigmacast.data.network.WireGuardTunnel
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.model.MotionQuality
import com.enigmacast.domain.reminder.Reminder
import com.enigmacast.domain.reminder.ReminderNotice
import com.enigmacast.live.AutoSwitcher
import com.enigmacast.live.OwnTunerSession
import com.enigmacast.live.ReminderScheduler
import com.enigmacast.playback.AppSleepTimer
import com.enigmacast.playback.LivePlaybackController
import com.enigmacast.playback.SleepEvent
import com.enigmacast.update.AppUpdateClient
import com.enigmacast.update.RemoteRelease
import com.enigmacast.update.isNewerRelease
import com.enigmacast.util.EnigmaLog
import com.enigmacast.util.UiText
import com.enigmacast.util.uiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class UpdatePrompt(
    val release: RemoteRelease,
    val progress: Int? = null,
    val message: UiText? = null,
    /** The APK is downloaded and handed to the installer; Update stays off. */
    val installing: Boolean = false,
)

@HiltViewModel
class AppViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    private val updates: AppUpdateClient,
    private val playback: LivePlaybackController,
    private val ownTuner: OwnTunerSession,
    private val playbackLifecycle: com.enigmacast.live.PlaybackLifecycle,
    private val playbackOwner: com.enigmacast.playback.PlaybackOwner,
    private val sleepTimer: AppSleepTimer,
    private val tunnel: WireGuardTunnel,
    private val reminders: ReminderScheduler,
    private val autoSwitcher: AutoSwitcher,
    private val recordings: com.enigmacast.live.RecordingBook,
    private val account: com.enigmacast.data.account.AccountRepository,
) : ViewModel() {
    var screen by mutableStateOf(AppScreen.Loading)
        private set

    init {
        // Settings › Appearance › GUI skins: repaints every screen and the OSD right away.
        viewModelScope.launch {
            settingsRepository.settings.map { it.osdSkin }.distinctUntilChanged()
                .collect { com.enigmacast.ui.theme.ActiveSkin.apply(it) }
        }
    }

    /** Settings › General › Navigation sounds, applied to every app window. */
    val navigationSounds: StateFlow<Boolean> = settingsRepository.settings
        .map { it.navigationSounds }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Settings › General › Motion. Premium unless the user picks Performance. */
    val motionQuality: StateFlow<MotionQuality> = settingsRepository.settings
        .map { it.motionQuality }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, MotionQuality.PREMIUM)
    var streamRunning by mutableStateOf(false)
        private set
    var updatePrompt by mutableStateOf<UpdatePrompt?>(null)
        private set
    var onDashboardBack: (() -> Boolean)? = null
    var requestFinish: (() -> Unit)? = null

    /** Back would leave the app; the activity asks before closing. */
    var exitPrompt by mutableStateOf(false)
        private set
    private var downloadJob: Job? = null

    /** The AutoSwitch countdown on screen, if any. */
    val reminderNotice: StateFlow<ReminderNotice?> = reminders.notice

    /** A short AutoSwitch problem message (the receiver would not zap). */
    var reminderMessage by mutableStateOf<UiText?>(null)
        private set
    private var reminderMessageJob: Job? = null

    private var returnScreen = AppScreen.Connection
    private var decided = false

    init {
        playback.allowPlayback()
        watchSleepTimer()
        viewModelScope.launch { recordings.ensureArmed() }
        viewModelScope.launch {
            reminders.switches.collect { autoSwitch(it) }
        }
        viewModelScope.launch {
            runCatching { settingsRepository.migrateSecrets() }
                .onFailure { EnigmaLog.service("secret migration failed: ${it.javaClass.simpleName}") }
            runCatching { settingsRepository.migrateChannelMemory() }
                .onFailure { EnigmaLog.service("channel memory migration failed: ${it.javaClass.simpleName}") }
        }
        viewModelScope.launch {
            val settings = settingsRepository.current()
            if (!decided) {
                decided = true
                val receiver = settings.activeReceiver()
                screen = when (com.enigmacast.domain.setup.SetupStep.startScreen(settings.receivers.size, settings.setupCompleted)) {
                    com.enigmacast.domain.setup.Start.DASHBOARD -> AppScreen.Dashboard
                    com.enigmacast.domain.setup.Start.SETUP -> AppScreen.Setup
                    com.enigmacast.domain.setup.Start.CONNECTION -> AppScreen.Connection
                }
                if (receiver != null) streamRunning = true
            }
        }
        // Plan check: on start, then every few hours while the app runs. Without network the
        // cached plan stays valid for Entitlements.OFFLINE_GRACE_MS.
        viewModelScope.launch {
            runCatching { account.refresh() }
            while (true) {
                delay(com.enigmacast.domain.account.Entitlements.REFRESH_INTERVAL_MS)
                runCatching { account.refresh() }
            }
        }
        viewModelScope.launch {
            val remote = runCatching { updates.check() }.getOrElse { error ->
                EnigmaLog.connection("update check failed: ${error.javaClass.simpleName}")
                null
            } ?: return@launch
            if (isNewerRelease(BuildConfig.VERSION_CODE, remote)) {
                updatePrompt = UpdatePrompt(remote)
            }
        }
    }

    /**
     * Cancel or Back in the update dialog. Stops a running download and lets the stream
     * that was stopped for it play again.
     */
    fun dismissUpdate() {
        downloadJob?.cancel()
        downloadJob = null
        updatePrompt = null
        playback.releaseHold(resume = true)
    }

    fun installUpdate() {
        val prompt = updatePrompt ?: return
        // This release cannot be installed over the current one: only OK is offered.
        if (prompt.release.reinstallRequired) {
            dismissUpdate()
            return
        }
        // A second press while downloading or installing does nothing.
        if (prompt.progress != null || prompt.installing || downloadJob?.isActive == true) return
        if (!updates.canRequestInstall()) {
            updatePrompt = prompt.copy(message = UiText.res(R.string.update_allow_install))
            runCatching { updates.openInstallPermission() }
            return
        }
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            // A live MPEG-TS stream takes most of the bandwidth; the APK would crawl.
            playback.holdForUpdate()
            updatePrompt = prompt.copy(progress = 0, message = null)
            val apk = runCatching {
                updates.download(prompt.release) { percent ->
                    withContext(Dispatchers.Main) {
                        updatePrompt = prompt.copy(progress = percent)
                    }
                }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                updatePrompt = prompt.copy(
                    progress = null,
                    message = UiText.res(
                        R.string.update_download_failed_retry,
                        error.uiMessage(UiText.res(R.string.update_download_failed)),
                    ),
                )
                return@launch
            }
            updatePrompt = prompt.copy(progress = null, message = null, installing = true)
            runCatching { updates.install(apk) }.onFailure { error ->
                updatePrompt = prompt.copy(message = error.uiMessage(UiText.res(R.string.update_installer_failed)), installing = false)
            }
        }
    }

    fun openDashboard() {
        decided = true
        screen = AppScreen.Dashboard
    }

    /** Settings › About › Run setup wizard again. */
    var setupAgain by mutableStateOf(false)
        private set

    fun openSetup() {
        decided = true
        setupAgain = true
        screen = AppScreen.Setup
    }

    /** Wizard finished: on a first run the stream starts now; a re-run returns to the dashboard. */
    fun finishSetup() {
        val again = setupAgain
        setupAgain = false
        streamRunning = true
        if (again) returnToSettingsHub = true
        openDashboard()
    }

    /** Back on the wizard's first step: a re-run returns to the dashboard; a first run asks to exit. */
    fun leaveSetup() {
        if (setupAgain) {
            setupAgain = false
            returnToSettingsHub = true
            openDashboard()
        } else {
            askExit()
        }
    }

    /**
     * Google Assistant / SEARCH: open Unified Search with [query].
     * [playFirst] is MEDIA_PLAY_FROM_SEARCH: tune the first strong hit after results arrive.
     * Stays on the player when already full screen so the OSD search overlay opens.
     * On Connection or Loading the query is kept until the dashboard is shown.
     */
    fun openAssistantSearch(query: String, playFirst: Boolean = false) {
        val text = query.trim()
        if (text.isEmpty()) return
        decided = true
        pendingSearchQuery = text
        pendingSearchPlay = playFirst
        pendingSearchToken += 1
        if (screen == AppScreen.Remote) return
        if (screen != AppScreen.Connection && screen != AppScreen.Loading) {
            screen = AppScreen.Dashboard
        }
    }

    fun assistantSearchConsumed() {
        pendingSearchQuery = null
        pendingSearchPlay = false
    }

    fun openStream() {
        decided = true
        focusPlayingChannel = false
        streamRunning = true
        screen = AppScreen.Remote
    }

    fun showMiniPlayer() {
        streamRunning = true
    }

    /**
     * Set when Back leaves the full-screen player: the dashboard then puts focus on the
     * channel that was playing instead of on the "Stream" menu item.
     */
    var focusPlayingChannel by mutableStateOf(false)
        private set

    /** Back from a recording: open My Items on this row instead of the programme grid. */
    var returnToMyItemsId by mutableStateOf<String?>(null)
        private set

    /** Assistant or SEARCH intent waiting to fill Unified Search. */
    var pendingSearchQuery by mutableStateOf<String?>(null)
        private set
    var pendingSearchPlay by mutableStateOf(false)
        private set
    var pendingSearchToken by mutableStateOf(0)
        private set

    fun showDashboard() {
        decided = true
        focusPlayingChannel = true
        returnToMyItemsId = null
        screen = AppScreen.Dashboard
    }

    fun showDashboardFromRecording(recordingId: String) {
        decided = true
        focusPlayingChannel = false
        returnToMyItemsId = recordingId
        screen = AppScreen.Dashboard
    }

    fun myItemsFocused() {
        returnToMyItemsId = null
    }

    fun playingChannelFocused() {
        focusPlayingChannel = false
    }

    /** Set just before the activity is recreated for a new language; Settings › Language then gets focus. */
    var focusLanguageSetting by mutableStateOf(false)
        private set

    /** Signal meter or Oscam was opened from the dashboard settings list; Back returns there. */
    var returnToSettingsHub by mutableStateOf(false)
        private set

    private var toolReturn = AppScreen.Settings

    fun requestLanguageFocus() {
        focusLanguageSetting = true
    }

    fun languageSettingFocused() {
        focusLanguageSetting = false
    }

    fun openConnection() {
        decided = true
        returnScreen = AppScreen.Connection
        screen = AppScreen.Connection
    }

    fun openSettings() {
        returnScreen = screen
        screen = AppScreen.Settings
    }

    fun openDiagnostics() {
        screen = AppScreen.Diagnostics
    }

    fun openSignalMeter() {
        toolReturn = if (screen == AppScreen.Dashboard) AppScreen.Dashboard else AppScreen.Settings
        screen = AppScreen.SignalMeter
    }

    fun openSettingsImport() {
        toolReturn = if (screen == AppScreen.Dashboard) AppScreen.Dashboard else AppScreen.Settings
        screen = AppScreen.SettingsImport
    }
    /** IPTV (XStreamity playlists) in full screen; Back returns to the dashboard. */
    fun openXtream() {
        screen = AppScreen.Xtream
    }

    fun openOscam() {
        toolReturn = if (screen == AppScreen.Dashboard) AppScreen.Dashboard else AppScreen.Settings
        screen = AppScreen.Oscam
    }

    fun settingsHubShown() {
        returnToSettingsHub = false
    }

    fun back() {
        screen = when (screen) {
            AppScreen.Xtream -> AppScreen.Dashboard
            AppScreen.SignalMeter, AppScreen.Oscam, AppScreen.SettingsImport -> {
                if (toolReturn == AppScreen.Dashboard) returnToSettingsHub = true
                toolReturn
            }
            AppScreen.Diagnostics -> AppScreen.Settings
            else -> returnScreen
        }
    }

    fun switchToReminderNow() = reminders.switchNow()

    fun cancelReminderNotice() = reminders.dismissNotice()

    /** The activity went to the background or came back; AutoSwitch only runs in the foreground. */
    fun setForeground(foreground: Boolean) = reminders.setForeground(foreground)

    /** The countdown ended: zap the way the viewer watches and go full screen. */
    private suspend fun autoSwitch(reminder: Reminder) {
        if (updatePrompt != null || screen == AppScreen.Loading || screen == AppScreen.Connection || screen == AppScreen.Setup) {
            EnigmaLog.service("autoswitch skipped on ${screen.name}")
            return
        }
        when (autoSwitcher.switchTo(reminder)) {
            AutoSwitcher.Result.SWITCHED -> openStream()
            AutoSwitcher.Result.ZAP_FAILED -> showReminderMessage(UiText.res(R.string.autoswitch_zap_failed, reminder.channelName))
            AutoSwitcher.Result.NOT_THIS_RECEIVER -> Unit
        }
    }

    private fun showReminderMessage(message: UiText) {
        reminderMessage = message
        reminderMessageJob?.cancel()
        reminderMessageJob = viewModelScope.launch {
            delay(REMINDER_MESSAGE_MS)
            reminderMessage = null
        }
    }

    private fun watchSleepTimer() {
        viewModelScope.launch {
            sleepTimer.events.collect { event ->
                if (event == SleepEvent.EXPIRED) {
                    EnigmaLog.player("sleep timer ran out, closing")
                    exit()
                }
            }
        }
    }

    fun askExit() {
        exitPrompt = true
    }

    fun dismissExit() {
        exitPrompt = false
    }

    fun confirmExit() {
        exitPrompt = false
        exit()
    }

    private var stoppedInBackground = false

    /**
     * The app left the screen (Home button, another app, Exit). The player is a singleton that
     * outlives the activity, so without this the sound went on behind the launcher. Everything
     * that plays is stopped and the box's stream (and so its tuner) is released.
     */
    fun stopForBackground() {
        if (stoppedInBackground) return
        stoppedInBackground = true
        EnigmaLog.player("app in background: stopping all playback and releasing the stream")
        ownTuner.deactivate()
        recordings.clearNowPlaying()
        playback.stop()
        playbackOwner.stopCurrent()
    }

    /** Back on screen: playback is allowed again and the player screen restarts its channel. */
    fun resumeFromBackground() {
        if (!stoppedInBackground) return
        stoppedInBackground = false
        EnigmaLog.player("app in foreground: playback allowed again")
        playback.allowPlayback()
        if (screen == AppScreen.Remote) screen = AppScreen.Dashboard
        playbackLifecycle.resumed()
    }

    fun exit() {
        EnigmaLog.player("exit: stopping all playback")
        stoppedInBackground = true
        recordings.clearNowPlaying()
        ownTuner.deactivate()
        playback.stop()
        playbackOwner.stopCurrent()
        streamRunning = false
        tunnel.shutdown()
        requestFinish?.invoke()
    }

    private companion object {
        const val REMINDER_MESSAGE_MS = 6_000L
    }
}
