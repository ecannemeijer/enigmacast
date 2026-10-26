package com.enigmacast.data.enigmasync

import android.content.Context
import com.enigmacast.domain.sync.SyncServerVersions
import com.enigmacast.domain.sync.SyncServerVersions.Verdict
import com.enigmacast.util.EnigmaLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Compares the paired receiver's sync server with the latest GitHub release and installs that
 * release over SSH when the owner agrees. Never updates on its own.
 */
@Singleton
class SyncServerUpdater @Inject constructor(
    @ApplicationContext context: Context,
    private val client: EnigmaSyncClient,
    private val engine: EnigmaSyncEngine,
    private val installer: SyncServerInstaller,
) {
    data class Check(
        val host: String,
        val running: SyncServerVersions.Running,
        val latest: String?,
        val verdict: Verdict,
    ) {
        /** "0.2.4", or a range for an old server that does not report its version. */
        val runningText: String get() = SyncServerVersions.describe(running)
    }

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class Checked(val check: Check) : State
        data class CheckFailed(val reason: String) : State
        data class Updating(val step: UpdateStep) : State
        data class Done(val version: String, val backup: String) : State
        data class Failed(val reason: UpdateFailure?, val detail: String) : State
    }

    private val prefs = context.getSharedPreferences("sync_server_update", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _last = MutableStateFlow<Check?>(null)
    /** The latest successful check, for the hotel notice and the startup prompt. */
    val last: StateFlow<Check?> = _last.asStateFlow()

    private val busy = Mutex()
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    }

    /** Null when this TV is not paired with a sync server. */
    suspend fun check(): Check? = withContext(Dispatchers.IO) {
        val config = client.config() ?: return@withContext null
        _state.value = State.Checking
        val reported = runCatching { client.serverVersion(config) }.getOrNull()
        val running = SyncServerVersions.Running(reported, prefs.getString(installedKey(config.host), null), engine.serverPages(config.host))
        val latest = runCatching { latestRelease() }.onFailure {
            EnigmaLog.service("syncserver: release check mislukt: ${it.javaClass.simpleName}")
        }.getOrNull()
        val result = Check(config.host, running, latest, SyncServerVersions.verdict(running, latest))
        EnigmaLog.service("syncserver: versie ${result.runningText}, laatste ${latest ?: "?"} → ${result.verdict}")
        _last.value = result
        _state.value = if (latest == null && result.verdict == Verdict.UNKNOWN) {
            State.CheckFailed("GitHub")
        } else {
            State.Checked(result)
        }
        result
    }

    /**
     * Startup check (also the first start after an app update). True when the owner should be asked
     * once: outdated, not in hotel mode, and not postponed for this release.
     */
    suspend fun startupPrompt(hotel: Boolean): Check? {
        if (hotel) {
            // Guests are never asked; the result still feeds the notice behind the hotel PIN.
            runCatching { check() }
            return null
        }
        val result = runCatching { check() }.getOrNull() ?: return null
        if (_state.value is State.Checked) _state.value = State.Idle
        return result.takeIf { SyncServerVersions.shouldPrompt(it.verdict, it.latest, prefs.getString(LATER, null), hotel = false) }
    }

    /** Later: no prompt again until a newer release appears. */
    fun postpone(latest: String?) {
        prefs.edit().putString(LATER, SyncServerVersions.normalize(latest)).apply()
    }

    fun reset() {
        if (_state.value !is State.Updating) _state.value = State.Idle
    }

    /**
     * Installs the latest release over SSH (port 22, password login). [password] is used for
     * this call only. Data, pairings, certificate and dashboard password stay as they are.
     */
    suspend fun update(user: String, password: String): Boolean = withContext(Dispatchers.IO) {
        if (!busy.tryLock()) return@withContext false
        try {
            val config = client.config() ?: return@withContext false
            _state.value = State.Updating(UpdateStep.CONNECT)
            val report = try {
                installer.update(config.host, user.ifBlank { "root" }, password, config.pin) { step ->
                    _state.value = State.Updating(step)
                }
            } catch (error: UpdateError) {
                EnigmaLog.service("syncserver: update geweigerd ${error.reason}")
                _state.value = State.Failed(error.reason, error.detail)
                return@withContext false
            } catch (error: Exception) {
                // JSch messages name the host and the cause ("Auth fail", "timeout"), never the password.
                EnigmaLog.service("syncserver: update mislukt ${error.javaClass.simpleName}")
                _state.value = State.Failed(null, error.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(160))
                return@withContext false
            }
            val version = report.after ?: report.before
            prefs.edit().putString(installedKey(config.host), version).apply()
            if (!report.changed) {
                // Already the latest release: nothing was stopped or installed.
                _state.value = State.Done(version, backup = "")
                _last.value = _last.value?.copy(running = SyncServerVersions.Running(null, version, engine.serverPages(config.host)), verdict = Verdict.UP_TO_DATE)
                return@withContext true
            }
            // The service needs a moment after the restart; the pinned call proves the same certificate.
            var answered: String? = null
            var reached = false
            repeat(15) {
                if (reached) return@repeat
                runCatching { client.serverVersion(config) }.onSuccess { answered = it; reached = true }
                if (!reached) delay(2_000)
            }
            if (!reached) {
                _state.value = State.Failed(UpdateFailure.VERIFY, "https://${config.host}:8740")
                return@withContext false
            }
            if (answered != null && SyncServerVersions.normalize(answered) != SyncServerVersions.normalize(version)) {
                _state.value = State.Failed(UpdateFailure.VERIFY, "${answered} ≠ $version")
                return@withContext false
            }
            EnigmaLog.service("syncserver: bijgewerkt ${report.before} → $version")
            _state.value = State.Done(version, report.backup)
            _last.value = Check(
                config.host,
                SyncServerVersions.Running(answered, version, engine.serverPages(config.host)),
                _last.value?.latest ?: version,
                Verdict.UP_TO_DATE,
            )
            true
        } finally {
            busy.unlock()
        }
    }

    /** Tag of the latest public release ("v0.3.0" → "0.3.0"). */
    private fun latestRelease(): String? {
        val request = Request.Builder().url(RELEASE).header("Accept", "application/vnd.github+json").get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("github ${response.code}")
            return SyncServerVersions.normalize(JSONObject(response.body.string()).optString("tag_name"))
        }
    }

    private fun installedKey(host: String) = "installed:$host"

    private companion object {
        const val RELEASE = "https://api.github.com/repos/ecannemeijer/enigmacast-enigma2-sync/releases/latest"
        const val LATER = "later-version"
    }
}
