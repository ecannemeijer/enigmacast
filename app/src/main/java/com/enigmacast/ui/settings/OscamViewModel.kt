package com.enigmacast.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.R
import com.enigmacast.data.network.ReceiverRouter
import com.enigmacast.data.oscam.OscamClient
import com.enigmacast.data.oscam.OscamException
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.oscam.OscamReader
import com.enigmacast.domain.oscam.OscamUser
import com.enigmacast.util.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class OscamConfirm { RESTART, STOP, RESTART_READER, RESET_READER_STATS, RESET_USER_STATS }

data class OscamUi(
    val port: String = "8888",
    val username: String = "",
    val password: String = "",
    val version: String = "",
    val uptime: String = "",
    val readers: List<OscamReader> = emptyList(),
    val users: List<OscamUser> = emptyList(),
    val log: String = "",
    val logPaused: Boolean = false,
    val debug: Int? = null,
    val busy: Boolean = false,
    val message: UiText? = null,
    val stopped: Boolean = false,
    val noReceiver: Boolean = false,
    val confirm: OscamConfirm? = null,
    val pendingReader: String? = null,
)

@HiltViewModel
class OscamViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val router: ReceiverRouter,
    private val oscam: OscamClient,
) : ViewModel() {
    var state by mutableStateOf(OscamUi())
        private set

    private var ready = false
    private var saveJob: Job? = null
    private var pollJob: Job? = null
    private var lastId = 0L
    private val lines = ArrayDeque<String>()

    init {
        viewModelScope.launch {
            val receiver = settingsRepository.current().activeReceiver()
            if (receiver == null) {
                state = state.copy(noReceiver = true)
                return@launch
            }
            state = state.copy(
                port = receiver.oscamPort.coerceIn(1, 65535).toString(),
                username = receiver.oscamUsername,
                password = receiver.oscamPassword,
            )
            ready = true
            connect()
        }
    }

    fun setPort(value: String) {
        state = state.copy(port = value.filter { it.isDigit() }.take(5))
        if (ready) scheduleSave()
    }

    fun setUsername(value: String) {
        state = state.copy(username = value)
        if (ready) scheduleSave()
    }

    fun setPassword(value: String) {
        state = state.copy(password = value)
        if (ready) scheduleSave()
    }

    fun connect() {
        if (state.noReceiver) return
        pollJob?.cancel()
        lastId = 0L
        lines.clear()
        state = state.copy(log = "", stopped = false, busy = true, message = null, readers = emptyList())
        pollJob = viewModelScope.launch {
            persistNow()
            val online = refreshStatus()
            refreshLog()
            state = state.copy(busy = false)
            if (!online) return@launch
            pollWhileOnline()
        }
    }

    fun askRestart() {
        if (state.busy || state.stopped) return
        state = state.copy(confirm = OscamConfirm.RESTART)
    }

    fun askStop() {
        if (state.busy || state.stopped) return
        state = state.copy(confirm = OscamConfirm.STOP)
    }

    fun askRestartReader(name: String) {
        if (state.busy || state.stopped || name.isBlank()) return
        state = state.copy(confirm = OscamConfirm.RESTART_READER, pendingReader = name)
    }

    fun askResetReaderStats() {
        if (state.busy || state.stopped) return
        state = state.copy(confirm = OscamConfirm.RESET_READER_STATS)
    }

    fun askResetUserStats() {
        if (state.busy || state.stopped) return
        state = state.copy(confirm = OscamConfirm.RESET_USER_STATS)
    }

    fun toggleLogPause() {
        val paused = !state.logPaused
        state = state.copy(
            logPaused = paused,
            log = if (paused) state.log else lines.joinToString("\n"),
        )
    }

    fun clearLog() {
        lines.clear()
        state = state.copy(log = "")
    }

    fun setDebug(level: Int) {
        if (state.busy || state.stopped || state.noReceiver) return
        viewModelScope.launch {
            val config = config() ?: return@launch
            val failed = runCatching { oscam.setDebug(config, level) }.exceptionOrNull()
            if (failed is CancellationException) throw failed
            if (failed != null) {
                state = state.copy(message = (failed as? OscamException)?.uiText ?: UiText.res(R.string.oscam_request_failed))
                return@launch
            }
            state = state.copy(debug = level, message = null)
        }
    }

    fun dismissConfirm() {
        if (!state.busy) state = state.copy(confirm = null, pendingReader = null)
    }

    fun confirmPending() {
        val action = state.confirm ?: return
        val reader = state.pendingReader
        if (action != OscamConfirm.RESTART && action != OscamConfirm.STOP) {
            state = state.copy(confirm = null, pendingReader = null, busy = true, message = null)
            viewModelScope.launch {
                val config = config() ?: run {
                    state = state.copy(busy = false, noReceiver = true)
                    return@launch
                }
                val failed = runCatching { runAction(config, action, reader) }.exceptionOrNull()
                if (failed is CancellationException) throw failed
                if (failed != null) {
                    state = state.copy(
                        busy = false,
                        message = (failed as? OscamException)?.uiText ?: UiText.res(R.string.oscam_request_failed),
                    )
                    return@launch
                }
                refreshStatus(reportError = false)
                state = state.copy(busy = false)
            }
            return
        }
        state = state.copy(confirm = null, pendingReader = null, busy = true, message = null)
        pollJob?.cancel()
        viewModelScope.launch {
            persistNow()
            val config = config() ?: run {
                state = state.copy(busy = false, noReceiver = true)
                return@launch
            }
            val dropped = runCatching { runAction(config, action, reader) }.exceptionOrNull()
            if (dropped is CancellationException) throw dropped
            if (dropped is OscamException && dropped.kind != OscamException.Kind.UNREACHABLE) {
                state = state.copy(busy = false, message = dropped.uiText)
                return@launch
            }
            if (dropped != null && dropped !is OscamException) {
                state = state.copy(busy = false, message = UiText.res(R.string.oscam_request_failed))
                return@launch
            }
            if (action == OscamConfirm.STOP) {
                state = state.copy(busy = false, stopped = true, readers = emptyList(), message = UiText.res(R.string.oscam_stopped))
                return@launch
            }
            state = state.copy(message = UiText.res(R.string.oscam_restarting), busy = true)
            repeat(RESTART_TRIES) {
                delay(POLL_MS)
                if (refreshStatus(reportError = false)) {
                    refreshLog()
                    state = state.copy(busy = false, message = null, stopped = false)
                    pollJob = viewModelScope.launch { pollWhileOnline() }
                    return@launch
                }
            }
            state = state.copy(busy = false, message = UiText.res(R.string.oscam_unreachable))
        }
    }

    private suspend fun pollWhileOnline() {
        var tick = 0
        while (currentCoroutineContext().isActive) {
            delay(POLL_MS)
            if (tick % STATUS_EVERY == 0 && !refreshStatus()) break
            refreshLog()
            tick += 1
        }
    }

    private suspend fun runAction(config: ReceiverConfig, action: OscamConfirm, reader: String?) {
        when (action) {
            OscamConfirm.RESTART -> oscam.restart(config)
            OscamConfirm.STOP -> oscam.stop(config)
            OscamConfirm.RESTART_READER -> oscam.restartReader(config, reader.orEmpty())
            OscamConfirm.RESET_READER_STATS -> oscam.resetReaderStats(config)
            OscamConfirm.RESET_USER_STATS -> oscam.resetUserStats(config)
        }
    }

    private suspend fun refreshStatus(reportError: Boolean = true): Boolean {
        val config = config() ?: return false
        return try {
            val server = oscam.snapshot(config)
            state = state.copy(
                version = server.version,
                uptime = server.uptime,
                readers = server.readers,
                users = server.users,
                message = null,
                stopped = false,
            )
            true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: OscamException) {
            if (reportError) state = state.copy(message = error.uiText)
            false
        }
    }

    private suspend fun refreshLog() {
        val config = config() ?: return
        try {
            val page = oscam.log(config, lastId)
            if (page.lastId != null) lastId = page.lastId
            if (page.debug != null && state.debug != page.debug) state = state.copy(debug = page.debug)
            page.lines.forEach { line ->
                lines.addLast(line)
                while (lines.size > MAX_LINES) lines.removeFirst()
            }
            if (page.lines.isNotEmpty() && !state.logPaused) state = state.copy(log = lines.joinToString("\n"))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: OscamException) {
            // The status call reports a dead web interface. A missing log page does not.
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DELAY_MS)
            writeCredentials()
        }
    }

    private suspend fun persistNow() {
        saveJob?.cancel()
        writeCredentials()
    }

    private suspend fun writeCredentials() {
        val snapshot = state
        val port = snapshot.port.toIntOrNull()?.coerceIn(1, 65535) ?: DEFAULT_PORT
        settingsRepository.update { current ->
            val active = current.activeReceiver() ?: return@update current
            val updated = active.copy(
                oscamPort = port,
                oscamUsername = snapshot.username,
                oscamPassword = snapshot.password,
            )
            current.copy(receivers = current.receivers.map { if (it.id == active.id) updated else it })
        }
    }

    private suspend fun config(): ReceiverConfig? {
        val active = settingsRepository.current().activeReceiver() ?: return null
        val port = state.port.toIntOrNull()?.coerceIn(1, 65535) ?: active.oscamPort
        return router.effective(
            active.copy(oscamPort = port, oscamUsername = state.username, oscamPassword = state.password),
        )
    }

    private companion object {
        const val POLL_MS = 1_000L
        const val STATUS_EVERY = 3
        const val MAX_LINES = 500
        const val SAVE_DELAY_MS = 400L
        const val RESTART_TRIES = 20
        const val DEFAULT_PORT = 8888
    }
}
