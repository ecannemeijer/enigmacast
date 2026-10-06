package com.enigmacast.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.BuildConfig
import com.enigmacast.R
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.live.OwnTunerSession
import com.enigmacast.playback.LivePlaybackController
import com.enigmacast.update.AppUpdateClient
import com.enigmacast.update.RemoteRelease
import com.enigmacast.update.isNewerRelease
import com.enigmacast.playback.AppSleepTimer
import com.enigmacast.playback.SleepEvent
import com.enigmacast.util.EnigmaLog
import com.enigmacast.util.UiText
import com.enigmacast.util.uiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class UpdatePrompt(
    val release: RemoteRelease,
    val progress: Int? = null,
    val message: UiText? = null,
)

@HiltViewModel
class AppViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    private val updates: AppUpdateClient,
    private val playback: LivePlaybackController,
    private val ownTuner: OwnTunerSession,
    private val sleepTimer: AppSleepTimer,
) : ViewModel() {
    var screen by mutableStateOf(AppScreen.Loading)
        private set
    var streamRunning by mutableStateOf(false)
        private set
    var updatePrompt by mutableStateOf<UpdatePrompt?>(null)
        private set
    var onDashboardBack: (() -> Boolean)? = null
    var requestFinish: (() -> Unit)? = null
    private var downloadJob: Job? = null

    private var returnScreen = AppScreen.Connection
    private var decided = false

    init {
        playback.allowPlayback()
        watchSleepTimer()
        viewModelScope.launch {
            runCatching { settingsRepository.migrateSecrets() }
                .onFailure { EnigmaLog.service("secret migration failed: ${it.javaClass.simpleName}") }
        }
        viewModelScope.launch {
            val settings = settingsRepository.current()
            if (!decided) {
                decided = true
                val receiver = settings.activeReceiver()
                screen = if (receiver != null) AppScreen.Dashboard else AppScreen.Connection
                if (receiver != null) streamRunning = true
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
            updatePrompt = prompt.copy(progress = null, message = null)
            runCatching { updates.install(apk) }.onFailure { error ->
                updatePrompt = prompt.copy(message = error.uiMessage(UiText.res(R.string.update_installer_failed)))
            }
        }
    }

    fun openDashboard() {
        decided = true
        screen = AppScreen.Dashboard
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

    fun showDashboard() {
        decided = true
        focusPlayingChannel = true
        screen = AppScreen.Dashboard
    }

    fun playingChannelFocused() {
        focusPlayingChannel = false
    }

    /** Set just before the activity is recreated for a new language; Settings › Language then gets focus. */
    var focusLanguageSetting by mutableStateOf(false)
        private set

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
        returnScreen = screen
        screen = AppScreen.Diagnostics
    }

    fun back() {
        screen = returnScreen
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

    fun exit() {
        ownTuner.deactivate()
        playback.stop()
        streamRunning = false
        requestFinish?.invoke()
    }
}
