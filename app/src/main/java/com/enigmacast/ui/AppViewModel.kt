package com.enigmacast.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.BuildConfig
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.playback.LivePlaybackController
import com.enigmacast.update.AppUpdateClient
import com.enigmacast.update.RemoteRelease
import com.enigmacast.update.isNewerRelease
import com.enigmacast.util.EnigmaLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class UpdatePrompt(
    val release: RemoteRelease,
    val progress: Int? = null,
    val message: String? = null,
)

@HiltViewModel
class AppViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    private val updates: AppUpdateClient,
    private val playback: LivePlaybackController,
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

    fun dismissUpdate() {
        downloadJob?.cancel()
        updatePrompt = null
    }

    fun installUpdate() {
        val prompt = updatePrompt ?: return
        if (!updates.canRequestInstall()) {
            updatePrompt = prompt.copy(message = "Allow this app to install updates, then press Update again.")
            runCatching { updates.openInstallPermission() }
            return
        }
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            updatePrompt = prompt.copy(progress = 0, message = null)
            val apk = runCatching {
                updates.download(prompt.release) { percent ->
                    withContext(Dispatchers.Main) {
                        updatePrompt = prompt.copy(progress = percent)
                    }
                }
            }.getOrElse { error ->
                updatePrompt = prompt.copy(progress = null, message = error.message ?: "Download failed")
                return@launch
            }
            updatePrompt = prompt.copy(progress = null, message = null)
            runCatching { updates.install(apk) }.onFailure { error ->
                updatePrompt = prompt.copy(message = error.message ?: "Could not open the installer")
            }
        }
    }

    fun openDashboard() {
        decided = true
        screen = AppScreen.Dashboard
    }

    fun openStream() {
        decided = true
        streamRunning = true
        screen = AppScreen.Remote
    }

    fun showMiniPlayer() {
        streamRunning = true
    }

    fun showDashboard() {
        decided = true
        screen = AppScreen.Dashboard
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

    fun exit() {
        playback.stop()
        streamRunning = false
        requestFinish?.invoke()
    }
}
