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
    private val settings: com.enigmacast.data.settings.SettingsRepository,
) {
    data class Check(
        val host: String,
        /** False for a server found at the receiver address that this TV is not paired with. */
        val paired: Boolean = true,
        /** Certificate pin of that server (from the pairing, or as it answered the probe). */
        val pin: String = "",
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
    /** One check at a time; callers right behind it (startup, card, prompt) get the same answer. */
    private val checking = Mutex()
    @Volatile
    private var lastAtMs = 0L
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    }

    /**
     * The paired server, or else a sync server answering at the receiver address ([host] when
     * given, e.g. from Settings › Sync server). Null when there is none.
     */
    suspend fun check(host: String? = null, force: Boolean = false): Check? = withContext(Dispatchers.IO) {
        checking.withLock { checkOnce(host, force) }
    }

    private suspend fun checkOnce(host: String?, force: Boolean): Check? {
        val config = client.config()
        val address = config?.host ?: host?.trim()?.ifBlank { null }
            ?: runCatching { settings.current().activeReceiver()?.host }.getOrNull()?.trim()?.ifBlank { null }
            ?: return null
        val previous = _last.value
        if (!force && previous != null && previous.paired == (config != null) &&
            com.enigmacast.domain.sync.SyncRunRules.reuseCheck(previous.host, lastAtMs, address, System.currentTimeMillis())
        ) {
            EnigmaLog.service("syncserver: versiecontrole hergebruikt (${previous.runningText})")
            if (_state.value !is State.Updating) _state.value = State.Checked(previous)
            return previous
        }
        _state.value = State.Checking
        val probe = runCatching { client.probe(address, 4, 6) }.getOrNull()
        if (config == null && probe == null) {
            _state.value = State.Idle
            return null
        }
        // A paired server answering with another certificate is not "our" server: no version from it.
        val reported = probe?.takeIf { config == null || it.pin == config.pin }?.version
        val running = SyncServerVersions.Running(reported, prefs.getString(installedKey(address), null), engine.serverPages(address))
        val latest = runCatching { latestRelease() }.onFailure {
            EnigmaLog.service("syncserver: release check mislukt: ${it.javaClass.simpleName}")
        }.getOrNull()
        val result = Check(address, config != null, config?.pin ?: probe!!.pin, running, latest, SyncServerVersions.verdict(running, latest))
        EnigmaLog.service("syncserver: versie ${result.runningText}, laatste ${latest ?: "?"} → ${result.verdict}${if (config == null) " (niet gekoppeld)" else ""}")
        _last.value = result
        val failed = latest == null && result.verdict == Verdict.UNKNOWN
        lastAtMs = if (failed) 0L else System.currentTimeMillis()
        _state.value = if (failed) State.CheckFailed("GitHub") else State.Checked(result)
        return result
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
            val target = client.config()?.let { it.host to it.pin }
                ?: _last.value?.takeIf { !it.paired && it.pin.isNotBlank() }?.let { it.host to it.pin }
                ?: return@withContext false
            val (host, pin) = target
            _state.value = State.Updating(UpdateStep.CONNECT)
            val report = try {
                installer.update(host, user.ifBlank { "root" }, password, pin) { step ->
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
            prefs.edit().putString(installedKey(host), version).apply()
            if (!report.changed) {
                // Already the latest release: nothing was stopped or installed.
                _state.value = State.Done(version, backup = "")
                _last.value = _last.value?.copy(running = SyncServerVersions.Running(null, version, engine.serverPages(host)), verdict = Verdict.UP_TO_DATE)
                return@withContext true
            }
            // The service needs a moment after the restart; the pinned call proves the same certificate.
            var answered: String? = null
            var reached = false
            repeat(15) {
                if (reached) return@repeat
                // Same certificate as before the update, or it is not the same server any more.
                runCatching { client.probe(host, 4, 6) }.getOrNull()?.takeIf { it.pin == pin }?.let { answered = it.version; reached = true }
                if (!reached) delay(2_000)
            }
            if (!reached) {
                _state.value = State.Failed(UpdateFailure.VERIFY, "https://$host:8740")
                return@withContext false
            }
            if (answered != null && SyncServerVersions.normalize(answered) != SyncServerVersions.normalize(version)) {
                _state.value = State.Failed(UpdateFailure.VERIFY, "${answered} ≠ $version")
                return@withContext false
            }
            EnigmaLog.service("syncserver: bijgewerkt ${report.before} → $version")
            // New server process (maybe new paging): no cached client, TLS choice, backoff or page size.
            engine.serverChanged(host)
            _state.value = State.Done(version, report.backup)
            lastAtMs = System.currentTimeMillis()
            _last.value = Check(
                host,
                _last.value?.paired ?: true,
                pin,
                SyncServerVersions.Running(answered, version, engine.serverPages(host)),
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
