package com.enigmacast.companionbridge

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.view.KeyEvent
import com.enigmacast.BuildConfig
import com.enigmacast.data.openwebif.OpenWebifApi
import com.enigmacast.data.profile.AvatarPhotos
import com.enigmacast.data.profile.ProfileCleanup
import com.enigmacast.data.profile.ProfileStore
import com.enigmacast.data.security.ProfilePinLock
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.model.AppSettings
import com.enigmacast.domain.model.ChannelMemory
import com.enigmacast.domain.model.LastChannel
import com.enigmacast.domain.model.PlayerState
import com.enigmacast.domain.model.PowerAction
import com.enigmacast.domain.model.StreamMode
import com.enigmacast.domain.profile.Profile
import com.enigmacast.domain.profile.ProfileRules
import com.enigmacast.domain.profile.ProfileState
import com.enigmacast.domain.security.PinCheck
import com.enigmacast.live.OwnTunerSession
import com.enigmacast.live.OwnTunerState
import com.enigmacast.playback.LivePlaybackController
import com.enigmacast.util.EnigmaLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

data class CompanionSnapshot(
    val tvName: String = "EnigmaCast TV",
    val appVersion: String = "",
    val profiles: List<Pair<String, String>> = emptyList(),
    val activeProfileId: String = "",
    val receiverName: String = "",
    val receiverOnline: Boolean = false,
    val mode: String = "own_tuner",
    val channelRef: String = "",
    val channelName: String = "",
    val channelNumber: Int = 0,
    val picon: String = "",
    val nowTitle: String? = null,
    val nowStartMs: Long = 0,
    val nowEndMs: Long = 0,
    val nextTitle: String? = null,
    val nextStartMs: Long? = null,
    val playing: Boolean = false,
    val paused: Boolean = false,
    val positionMs: Long = 0,
    val tunerUsed: Int = 0,
    val tunerTotal: Int = 0,
    val tunerStatus: String = "IDLE",
    val source: String = "idle",
    val screenApp: String = "dashboard",
)

data class CompanionBridgeState(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val port: Int = CompanionProtocol.PORT,
    val pairingCode: String? = null,
    val pairingExpiresAt: Long = 0,
    val pin: String = "",
    val error: String? = null,
    val devices: List<CompanionDevice> = emptyList(),
    val clients: Int = 0,
    val testFormText: String = "",
)

/**
 * De TV-kant van de Companion-koppeling. Start alleen op de voorgrond (zie ADR 0006) en
 * alleen als de gebruiker hem in Settings aanzet.
 */
@Singleton
class CompanionBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val profiles: ProfileStore,
    private val ownTuner: OwnTunerSession,
    private val playback: LivePlaybackController,
    private val openWebif: OpenWebifApi,
    private val devices: CompanionDevices,
    private val auth: CompanionAuth,
    private val formHost: CompanionFormHost,
    private val settingsHost: CompanionSettingsHost,
    private val libraryHost: CompanionLibraryHost,
    private val photos: AvatarPhotos,
    private val pins: ProfilePinLock,
    private val cleanup: ProfileCleanup,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("companion_bridge", Context.MODE_PRIVATE)
    private val pairing = CompanionPairing()
    private val _state = MutableStateFlow(
        CompanionBridgeState(enabled = prefs.getBoolean(KEY_ENABLED, false)),
    )
    val state: StateFlow<CompanionBridgeState> = _state.asStateFlow()

    var hooks: CompanionHooks = CompanionHooks()

    @Volatile
    private var snapshot = CompanionSnapshot()

    private var server: CompanionServer? = null
    private var activity: WeakReference<Activity>? = null
    private var stopJob: Job? = null

    @Volatile
    private var recentJsonState: JsonObject = buildJsonObject { }

    init {
        CompanionFormHub.host = formHost
        scope.launch { devices.load() }
        scope.launch {
            devices.devices.collect { list -> update { it.copy(devices = list) } }
        }
        scope.launch {
            formHost.state.collect { formState ->
                formState?.let {
                    android.util.Log.d("CompanionForm", "broadcast ${it.formId} closed=${it.closed}")
                    server?.broadcastForm(formJson(it))
                }
            }
        }
        scope.launch {
            settingsHost.state.collect { settingsState ->
                settingsState?.let { server?.broadcastSettings(settingsJson(it)) }
            }
        }
        scope.launch {
            profiles.state.collect { server?.broadcastProfiles(profilesJson()) }
        }
        scope.launch {
            settings.settings.collect { current ->
                val json = recentJson(current)
                recentJsonState = json
                server?.broadcastRecent(json)
            }
        }
        scope.launch {
            libraryHost.state.collect { server?.broadcastLibrary(libraryJson(it)) }
        }
        scope.launch {
            combine(settings.settings, profiles.state, ownTuner.state, playback.state) { s, p, o, pl ->
                buildSnapshot(s, p, o, pl)
            }
                .distinctUntilChanged()
                .collect { fresh ->
                    snapshot = fresh
                    server?.broadcastState()
                }
        }
        scope.launch {
            while (true) {
                delay(1_000)
                if (_state.value.pairingCode != null && pairing.current() == null) {
                    update { it.copy(pairingCode = null) }
                }
            }
        }
    }

    // ------------------------------------------------------------ lifecycle

    fun onForeground(activity: Activity) {
        this.activity = WeakReference(activity)
        stopJob?.cancel()
        stopJob = null
        if (_state.value.enabled && server == null) start()
    }

    fun onBackground() {
        if (server == null) return
        stopJob?.cancel()
        stopJob = scope.launch {
            delay(30_000)
            stop()
        }
    }

    fun shutdown() {
        runCatching { server?.stop() }
        server = null
        pairing.clear()
        _state.update {
            it.copy(running = false, pairingCode = null, pairingExpiresAt = 0, clients = 0)
        }
        // De scope blijft leven: dezelfde singleton kan opnieuw starten als de app weer opent.
    }

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        update { it.copy(enabled = value, error = null) }
        if (value) {
            if (activity != null) start() else update { it.copy(error = "Open EnigmaCast om te starten") }
        } else {
            stop()
        }
    }

    fun newPairingCode() {
        if (server == null) return
        val code = pairing.newCode()
        update { it.copy(pairingCode = code, pairingExpiresAt = pairing.expiresAt()) }
    }

    fun revoke(id: String) {
        scope.launch {
            devices.revoke(id)
            server?.broadcastState()
        }
    }

    /** Alleen voor het testveld in het paneel: de waarde blijft in het bridge-stateobject. */
    fun setTestFormText(text: String) {
        update { it.copy(testFormText = text) }
    }

    private fun start() {
        if (!BuildConfig.COMPANION_BRIDGE) return
        if (server != null) return
        scope.launch {
            val pin = runCatching { CompanionTls.pin() }.getOrElse { error ->
                update { it.copy(error = "TLS: ${error.message ?: error.javaClass.simpleName}") }
                return@launch
            }
            runCatching { devices.load() }
            val created = CompanionServer(
                port = CompanionProtocol.PORT,
                tvId = tvId(),
                tvName = tvName(),
                appVersion = BuildConfig.VERSION_NAME,
                auth = auth,
                devices = devices,
                pairing = pairing,
                hello = { helloEnvelope() },
                state = { stateEnvelope() },
                form = { formHost.state.value?.takeIf { !it.closed }?.let(::formJson) },
                settings = { settingsHost.state.value?.let(::settingsJson) },
                library = { libraryJson(libraryHost.state.value) },
                recent = { recentJsonState },
                onPhoto = { request -> savePhoto(request) },
                onPhotoRead = { id -> readPhoto(id) },
                onPhotoDelete = { id -> deletePhoto(id) },
                onCommand = { envelope -> handleCommand(envelope) },
                onClients = { count -> update { it.copy(clients = count) } },
                log = { message -> EnigmaLog.service(message) },
            )
            val result = created.start()
            if (result.isSuccess) {
                server = created
                val code = pairing.newCode()
                update {
                    it.copy(
                        running = true,
                        port = CompanionProtocol.PORT,
                        pin = pin,
                        pairingCode = code,
                        pairingExpiresAt = pairing.expiresAt(),
                        error = null,
                    )
                }
            } else {
                runCatching { created.stop() }
                update {
                    it.copy(
                        running = false,
                        error = result.exceptionOrNull()?.message ?: "Brug starten mislukt",
                    )
                }
            }
        }
    }

    private fun stop() {
        runCatching { server?.stop() }
        server = null
        pairing.clear()
        update { it.copy(running = false, pairingCode = null, pairingExpiresAt = 0, clients = 0) }
    }

    // ------------------------------------------------------------ toestand

    private fun buildSnapshot(
        settings: AppSettings,
        profiles: ProfileState,
        own: OwnTunerState,
        player: PlayerState,
    ): CompanionSnapshot {
        val receiver = settings.activeReceiver()
        val channel = own.channel
        val events = own.guide?.events.orEmpty()
        val nowIndex = events.indexOfFirst { it.current }
        val index = if (nowIndex >= 0) {
            nowIndex
        } else {
            val now = System.currentTimeMillis()
            events.indexOfFirst {
                val start = it.beginEpochSec * 1000
                start <= now && start + it.durationSec * 1000 > now
            }
        }
        val now = events.getOrNull(index)
        val next = if (index >= 0) events.getOrNull(index + 1) else null
        return CompanionSnapshot(
            tvName = tvName(),
            appVersion = BuildConfig.VERSION_NAME,
            profiles = profiles.profiles.map { it.id to it.name },
            activeProfileId = profiles.activeId,
            receiverName = receiver?.name.orEmpty(),
            receiverOnline = receiver != null,
            mode = if (settings.streamMode == StreamMode.OWN_TUNER) "own_tuner" else "follow",
            channelRef = channel?.reference.orEmpty(),
            channelName = channel?.name.orEmpty(),
            channelNumber = channel?.number ?: 0,
            picon = channel?.picon.orEmpty(),
            nowTitle = now?.title,
            nowStartMs = (now?.beginEpochSec ?: 0L) * 1000,
            nowEndMs = ((now?.beginEpochSec ?: 0L) + (now?.durationSec ?: 0L)) * 1000,
            nextTitle = next?.title,
            nextStartMs = next?.beginEpochSec?.times(1000),
            playing = player.playing,
            paused = player.timeshiftPaused,
            positionMs = player.positionMs,
            tunerUsed = own.tuner.used,
            tunerTotal = own.tuner.total,
            tunerStatus = own.tuner.status.name,
            source = if (channel != null) "enigma2" else "idle",
            screenApp = hooks.screenName(),
        )
    }

    private fun helloEnvelope(): CompanionEnvelope {
        val snap = snapshot
        return CompanionProtocol.envelope(
            CompanionProtocol.TYPE_HELLO,
            buildJsonObject {
                put("tv", CompanionProtocol.tvJson(tvId(), tvName(), BuildConfig.VERSION_NAME))
                put("api", "v1")
                putJsonArray("capabilities") { CompanionProtocol.CAPABILITIES.forEach { add(it) } }
                put(
                    "screen",
                    buildJsonObject {
                        put("app", snap.screenApp)
                        put("section", "home")
                    },
                )
                put("profile", profilesJson())
                put(
                    "receiver",
                    buildJsonObject {
                        put("id", snap.receiverName.lowercase().replace(' ', '-'))
                        put("name", snap.receiverName)
                        put("online", snap.receiverOnline)
                        put("mode", snap.mode)
                    },
                )
                put("settingsRevision", 1)
            },
            seq = 0,
        )
    }

    private fun profilesJson(): JsonObject {
        val current = profiles.state.value
        return CompanionProtocol.profilesJson(
            current.activeId,
            current.profiles.map {
                CompanionProtocol.CompanionProfile(
                    it.id,
                    it.name,
                    it.avatar,
                    it.photo,
                    pins.hasPin(it.uuid.ifBlank { ProfileState.LEGACY_UUID }),
                )
            },
        )
    }

    private fun recentJson(settings: AppSettings): JsonObject {
        val receiver = settings.activeReceiver()
        val recent = if (receiver == null) emptyList() else ChannelMemory.recentFor(settings, receiver.id)
        return buildJsonObject {
            put("receiverId", receiver?.id.orEmpty())
            put("receiverName", receiver?.name.orEmpty())
            put("receiverHost", receiver?.host.orEmpty())
            putJsonArray("channels") {
                recent.forEach { channel ->
                    add(
                        buildJsonObject {
                            put("reference", channel.reference)
                            put("name", channel.name)
                            put("bouquet", channel.bouquetReference)
                        },
                    )
                }
            }
        }
    }

    private fun libraryJson(state: CompanionLibraryHost.State): JsonObject = buildJsonObject {
        putJsonArray("favorites") {
            state.favorites.forEach { row ->
                add(
                    buildJsonObject {
                        put("title", row.title)
                        put("url", row.url)
                        put("source", row.source)
                        put("kind", row.kind)
                        put("id", row.id)
                        put("positionMs", 0L)
                        put("durationMs", 0L)
                        put("favorite", true)
                        put("updatedMs", row.addedMs)
                    },
                )
            }
        }
        putJsonArray("resume") {
            state.resumes.forEach { row ->
                add(
                    buildJsonObject {
                        put("title", row.title)
                        put("url", row.url)
                        put("source", row.source)
                        put("kind", row.kind)
                        put("id", row.id)
                        put("positionMs", row.positionMs)
                        put("durationMs", row.durationMs)
                        put("favorite", false)
                        put("updatedMs", row.updatedMs)
                    },
                )
            }
        }
    }

    private suspend fun rememberRecent(payload: JsonObject): Boolean {
        val reference = payload.string("reference").trim()
        if (reference.isBlank()) return false
        val host = payload.string("receiverHost").trim()
        val current = settings.current()
        val receiver = current.activeReceiver() ?: return false
        if (host.isNotBlank() && !receiver.host.equals(host, ignoreCase = true)) return false
        val name = payload.string("name").trim()
        val bouquet = payload.string("bouquet").trim()
        settings.update { state ->
            ChannelMemory.remember(state, receiver.id, LastChannel(reference, name, bouquet))
        }
        return true
    }

    private fun library(payload: JsonObject): Boolean {
        val action = payload.string("action")
        val entry = payload["entry"] as? JsonObject ?: return false
        return libraryHost.apply(
            action,
            CompanionLibraryHost.Entry(
                title = entry.string("title"),
                url = entry.string("url"),
                source = entry.string("source"),
                kind = entry.string("kind"),
                id = entry.string("id"),
                positionMs = (entry["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0L,
                durationMs = (entry["durationMs"] as? JsonPrimitive)?.longOrNull ?: 0L,
                favorite = (entry["favorite"] as? JsonPrimitive)?.booleanOrNull == true,
                updatedMs = (entry["updatedMs"] as? JsonPrimitive)?.longOrNull ?: 0L,
            ),
        )
    }

    private fun settingsJson(state: CompanionSettingsHost.State): JsonObject = buildJsonObject {        put("revision", state.revision)
        put("locked", state.locked)
        putJsonArray("entries") {
            state.entries.forEach { entry ->
                add(
                    buildJsonObject {
                        put("path", entry.path)
                        put("label", entry.label)
                        put("description", entry.description)
                        put("category", entry.category)
                        put("type", entry.type)
                        put("value", entry.value)
                        putJsonArray("options") {
                            entry.options.forEach { option ->
                                add(buildJsonObject { put("value", option.value); put("label", option.label) })
                            }
                        }
                        entry.min?.let { put("min", it) }
                        entry.max?.let { put("max", it) }
                        entry.step?.let { put("step", it) }
                        put("dangerous", entry.dangerous)
                        put("requiresRestart", entry.requiresRestart)
                    },
                )
            }
        }
    }

    private suspend fun savePhoto(request: PhotoRequest): PhotoResult {
        val profile = profiles.state.value.profiles.firstOrNull {
            it.uuid == request.profileId || it.id == request.profileId
        } ?: return PhotoResult.Rejected("profile")
        val uuid = profile.uuid.ifBlank { ProfileState.LEGACY_UUID }
        if (pins.hasPin(uuid)) return PhotoResult.Rejected("pin")
        return try {
            photos.save(uuid, request.bytes, request.x, request.y, request.side)
            val stamp = System.currentTimeMillis()
            profiles.setPhoto(profile.id, stamp)
            PhotoResult.Saved(stamp)
        } catch (error: AvatarPhotos.Rejected) {
            PhotoResult.Rejected(error.code)
        } catch (error: Exception) {
            PhotoResult.Rejected("image")
        }
    }

    private suspend fun readPhoto(id: String): ByteArray? = withContext(Dispatchers.IO) {
        val profile = profiles.state.value.profiles.firstOrNull { it.uuid == id || it.id == id }
            ?: return@withContext null
        val file = photos.file(profile.uuid.ifBlank { ProfileState.LEGACY_UUID })
        if (file.exists()) runCatching { file.readBytes() }.getOrNull() else null
    }

    private suspend fun deletePhoto(id: String): Boolean = withContext(Dispatchers.IO) {
        val profile = profiles.state.value.profiles.firstOrNull { it.uuid == id || it.id == id }
            ?: return@withContext false
        photos.delete(profile.uuid.ifBlank { ProfileState.LEGACY_UUID })
        profiles.clearPhoto(profile.id)
        true
    }

    private fun formJson(state: CompanionFormState): JsonObject = buildJsonObject {        put("formId", state.formId)
        put("title", state.title)
        put("revision", state.revision)
        put("closed", state.closed)
        putJsonArray("fields") {
            if (!state.closed) {
                state.fields.forEach { field ->
                    add(
                        buildJsonObject {
                            put("id", field.id)
                            put("label", field.label)
                            put("type", field.type)
                            put("masked", field.masked)
                            put("sensitive", field.masked)
                            if (!field.masked) put("value", field.value)
                            put("required", false)
                        },
                    )
                }
            }
        }
        put("errors", buildJsonObject { })
    }

    private fun stateEnvelope(): CompanionEnvelope {
        val snap = snapshot
        val payload = buildJsonObject {
            put("source", snap.source)
            if (snap.channelRef.isNotBlank()) {
                put(
                    "channel",
                    buildJsonObject {
                        put("ref", snap.channelRef)
                        put("name", snap.channelName)
                        put("number", snap.channelNumber)
                        put("picon", snap.picon)
                    },
                )
            }
            val title = snap.nowTitle
            if (title != null) {
                val duration = (snap.nowEndMs - snap.nowStartMs).coerceAtLeast(1)
                val progress = ((System.currentTimeMillis() - snap.nowStartMs).toDouble() / duration)
                    .coerceIn(0.0, 1.0)
                put(
                    "now",
                    buildJsonObject {
                        put("title", title)
                        put("startMs", snap.nowStartMs)
                        put("endMs", snap.nowEndMs)
                        put("progress", progress)
                        snap.nextTitle?.let { put("nextTitle", it) }
                        snap.nextStartMs?.let { put("nextStartMs", it) }
                    },
                )
            }
            put(
                "player",
                buildJsonObject {
                    put("playing", snap.playing)
                    put("paused", snap.paused)
                    put("positionMs", snap.positionMs)
                    put("durationMs", 0L)
                    put("bufferedMs", 0L)
                },
            )
            put(
                "tuner",
                buildJsonObject {
                    put("used", snap.tunerUsed)
                    put("total", snap.tunerTotal)
                    put("status", snap.tunerStatus)
                },
            )
            put("route", "home")
        }
        return CompanionProtocol.envelope(CompanionProtocol.TYPE_STATE_PLAYER, payload)
    }

    // ------------------------------------------------------------ commando's

    private suspend fun handleCommand(envelope: CompanionEnvelope): CompanionEnvelope {
        val started = System.nanoTime()
        val payload = envelope.payload as? JsonObject ?: buildJsonObject { }
        return when (envelope.type) {
            CompanionProtocol.TYPE_CMD_KEY -> {
                if (dispatchKey(payload)) {
                    ack(envelope, started)
                } else {
                    error(envelope, CompanionProtocol.ERR_NOT_FOUND, "Onbekende toets")
                }
            }
            CompanionProtocol.TYPE_CMD_NAV -> {
                dispatchNav(payload)
                ack(envelope, started)
            }
            CompanionProtocol.TYPE_CMD_ZAP -> {
                if (zap(payload)) {
                    ack(envelope, started)
                } else {
                    error(envelope, CompanionProtocol.ERR_NOT_FOUND, "Zender niet gevonden")
                }
            }
            CompanionProtocol.TYPE_CMD_VOLUME -> {
                adjustVolume(payload)
                ack(envelope, started)
            }
            CompanionProtocol.TYPE_CMD_PLAYER -> {
                player(payload)
                ack(envelope, started)
            }
            CompanionProtocol.TYPE_CMD_FORM -> {
                val formId = payload.string("formId")
                val action = payload.string("action").ifBlank { "SUBMIT" }
                android.util.Log.d("CompanionForm", "cmd.form action=$action id=$formId")
                val values = (payload["values"] as? JsonObject)
                    ?.mapValues { (_, value) -> (value as? JsonPrimitive)?.content.orEmpty() }
                    .orEmpty()
                when (action) {
                    "TEXT" -> values.forEach { (fieldId, text) -> formHost.update(formId, fieldId, text) }
                    "CANCEL" -> formHost.cancel(formId)
                    else -> formHost.submit(formId, values)
                }
                ack(envelope, started)
            }
            CompanionProtocol.TYPE_CMD_SETTINGS -> {
                val baseRevision = (payload["baseRevision"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
                val ops = (payload["ops"] as? JsonArray)
                    ?.mapNotNull { it as? JsonObject }
                    ?.map { CompanionSettingsHost.Op(it.string("path"), it["value"]) }
                    .orEmpty()
                when (val result = settingsHost.apply(baseRevision, ops, payload.string("pin"))) {
                    CompanionSettingsHost.ApplyResult.Applied -> ack(envelope, started)
                    CompanionSettingsHost.ApplyResult.Conflict ->
                        error(envelope, CompanionProtocol.ERR_SETTINGS_CONFLICT, "Instellingen zijn gewijzigd")
                    CompanionSettingsHost.ApplyResult.Locked ->
                        error(envelope, CompanionProtocol.ERR_SETTINGS_LOCKED, "Settings PIN required")
                    is CompanionSettingsHost.ApplyResult.Rejected ->
                        error(envelope, CompanionProtocol.ERR_NOT_FOUND, "Onbekende instelling: ${result.path}")
                }
            }
            CompanionProtocol.TYPE_CMD_PROFILE -> {
                val failure = profile(payload)
                if (failure == null) ack(envelope, started) else error(envelope, CompanionProtocol.ERR_VALIDATION, failure)
            }
            CompanionProtocol.TYPE_CMD_LIBRARY -> {
                if (library(payload)) ack(envelope, started) else error(
                    envelope,
                    CompanionProtocol.ERR_VALIDATION,
                    "LIBRARY_INVALID",
                )
            }
            CompanionProtocol.TYPE_CMD_RECENT -> {
                if (rememberRecent(payload)) ack(envelope, started) else error(
                    envelope,
                    CompanionProtocol.ERR_VALIDATION,
                    "RECENT_RECEIVER_MISMATCH",
                )
            }
            CompanionProtocol.TYPE_CMD_POWER -> {
                power(payload)
                ack(envelope, started)
            }
            CompanionProtocol.TYPE_CMD_SYNC -> ack(envelope, started)
            else -> error(envelope, CompanionProtocol.ERR_NOT_FOUND, "Onbekend commando")
        }
    }

    private fun dispatchKey(payload: JsonObject): Boolean {
        val key = payload.string("key")
        val phase = payload.string("phase")
        val repeat = payload.int("repeat") ?: 0
        if (key == "VOLUME_UP" || key == "VOLUME_DOWN" || key == "MUTE") {
            adjustVolume(
                buildJsonObject {
                    put("target", "TV")
                    put("delta", if (key == "VOLUME_UP") 1 else if (key == "VOLUME_DOWN") -1 else 0)
                    if (key == "MUTE") put("mute", true)
                },
            )
            return true
        }
        if (key == "HOME") {
            main.post { hooks.openHome() }
            return true
        }
        val code = keyCode(key) ?: return false
        val action = if (phase == "UP") KeyEvent.ACTION_UP else KeyEvent.ACTION_DOWN
        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(now, now, action, code, repeat)
        val target = activity?.get() ?: return false
        main.post { target.dispatchKeyEvent(event) }
        return true
    }

    private fun dispatchNav(payload: JsonObject) {
        when (payload.string("target")) {
            "HOME", "DASHBOARD" -> main.post { hooks.openHome() }
            "PLAYER" -> main.post { hooks.openPlayer() }
            "SETTINGS" -> main.post { hooks.openSettings() }
            else -> Unit
        }
    }

    private fun zap(payload: JsonObject): Boolean {
        val number = payload.int("number")
            ?: ownTuner.state.value.channels.firstOrNull { it.reference == payload.string("serviceRef") }?.number
            ?: return false
        return ownTuner.zapNumber(number)
    }

    private fun adjustVolume(payload: JsonObject) {
        val target = payload.string("target")
        if (target == "PHONE") return
        val delta = payload.int("delta") ?: 0
        val mute = payload.bool("mute")
        main.post {
            val audio = context.getSystemService(AudioManager::class.java) ?: return@post
            when {
                mute != null && mute -> audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_TOGGLE_MUTE,
                    AudioManager.FLAG_SHOW_UI,
                )
                delta > 0 -> audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE,
                    AudioManager.FLAG_SHOW_UI,
                )
                delta < 0 -> audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI,
                )
            }
        }
    }

    private fun player(payload: JsonObject) {
        when (payload.string("action")) {
            "PLAY", "PAUSE" -> main.post { playback.toggleTimeshiftPause() }
            "STOP" -> main.post { playback.stop() }
            "SEEK" -> payload.long("positionMs")?.let { position ->
                main.post { playback.seekFile(position) }
            }
            else -> Unit
        }
    }

    private suspend fun profile(payload: JsonObject): String? {
        val action = payload.string("action")
        val uuid = payload.string("uuid")
        val pin = payload.string("pin")
        val state = profiles.state.value
        val target = state.profiles.firstOrNull { it.uuid == uuid || it.id == uuid }
        when (action) {
            "SELECT" -> {
                if (target == null) return "PROFILE_NOT_FOUND"
                if (target.id != state.activeId) pinError(target, pin)?.let { return it }
                profiles.switchTo(target.id)
            }
            "CREATE" -> {
                if (state.profiles.size >= MAX_PROFILES) return "PROFILE_LIMIT"
                val name = payload.string("name").trim()
                if (name.isEmpty() || name.length > 20) return "PROFILE_NAME_INVALID"
                val avatar = (payload.int("avatar") ?: 0).coerceIn(0, ProfileState.AVATAR_COUNT - 1)
                profiles.add(name, avatar)
            }
            "RENAME" -> {
                if (target == null) return "PROFILE_NOT_FOUND"
                pinError(target, pin)?.let { return it }
                val name = payload.string("name").trim()
                if (name.isEmpty() || name.length > 20) return "PROFILE_NAME_INVALID"
                profiles.edit(target.id, name, target.avatar)
                if (payload.bool("clearPhoto") == true) clearPhoto(target)
            }
            "SET_AVATAR" -> {
                if (target == null) return "PROFILE_NOT_FOUND"
                pinError(target, pin)?.let { return it }
                val avatar = (payload.int("avatar") ?: target.avatar).coerceIn(0, ProfileState.AVATAR_COUNT - 1)
                profiles.edit(target.id, target.name, avatar)
                if (payload.bool("clearPhoto") == true) clearPhoto(target)
            }
            "DELETE" -> {
                if (target == null) return "PROFILE_NOT_FOUND"
                if (!ProfileRules.canDelete(state, target.id)) return "PROFILE_CANNOT_DELETE"
                pinError(target, pin)?.let { return it }
                profiles.delete(target.id)
                cleanup.purge(target)
            }
            else -> return "PROFILE_ACTION_UNKNOWN"
        }
        return null
    }

    private fun pinError(profile: Profile, pin: String): String? {
        val uuid = profile.uuid.ifBlank { ProfileState.LEGACY_UUID }
        if (!pins.hasPin(uuid)) return null
        return when (pins.check(uuid, pin)) {
            PinCheck.Ok -> null
            is PinCheck.Wrong -> "PROFILE_PIN_WRONG"
            is PinCheck.CoolingDown -> "PROFILE_PIN_COOLDOWN"
        }
    }

    private fun clearPhoto(profile: Profile) {
        photos.delete(profile.uuid.ifBlank { ProfileState.LEGACY_UUID })
        profiles.clearPhoto(profile.id)
    }

    private fun power(payload: JsonObject) {
        val action = when (payload.string("action")) {
            "STANDBY" -> PowerAction.STANDBY
            "WAKE" -> PowerAction.WAKE_UP
            "REBOOT" -> PowerAction.REBOOT
            "RESTART_GUI" -> PowerAction.RESTART_GUI
            "DEEP_STANDBY" -> PowerAction.DEEP_STANDBY
            else -> null
        } ?: return
        scope.launch {
            val receiver = settings.current().activeReceiver() ?: return@launch
            runCatching { openWebif.setPowerState(receiver, action) }
        }
    }

    // ------------------------------------------------------------ helpers

    private fun ack(envelope: CompanionEnvelope, started: Long): CompanionEnvelope =
        CompanionProtocol.envelope(
            CompanionProtocol.TYPE_ACK,
            CompanionProtocol.ackPayload((System.nanoTime() - started) / 1_000_000),
            id = envelope.id,
        )

    private fun error(envelope: CompanionEnvelope, code: String, message: String): CompanionEnvelope =
        CompanionProtocol.envelope(
            CompanionProtocol.TYPE_ERROR,
            CompanionProtocol.errorPayload(code, message),
            id = envelope.id,
        )

    private fun update(transform: (CompanionBridgeState) -> CompanionBridgeState) {
        _state.update(transform)
    }

    private fun tvId(): String {
        val androidId = runCatching {
            AndroidSettings.Secure.getString(context.contentResolver, AndroidSettings.Secure.ANDROID_ID)
        }.getOrNull()
        return "ec-" + (androidId?.takeIf { it.isNotBlank() } ?: "tv")
    }

    private fun tvName(): String = Build.MODEL?.trim()?.takeIf { it.isNotEmpty() } ?: "EnigmaCast TV"

    private fun keyCode(key: String): Int? = when (key) {
        "DPAD_UP" -> KeyEvent.KEYCODE_DPAD_UP
        "DPAD_DOWN" -> KeyEvent.KEYCODE_DPAD_DOWN
        "DPAD_LEFT" -> KeyEvent.KEYCODE_DPAD_LEFT
        "DPAD_RIGHT" -> KeyEvent.KEYCODE_DPAD_RIGHT
        "OK" -> KeyEvent.KEYCODE_DPAD_CENTER
        "BACK" -> KeyEvent.KEYCODE_BACK
        "MENU" -> KeyEvent.KEYCODE_MENU
        "PLAY_PAUSE" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        "STOP" -> KeyEvent.KEYCODE_MEDIA_STOP
        "FAST_FORWARD" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
        "REWIND" -> KeyEvent.KEYCODE_MEDIA_REWIND
        "NEXT" -> KeyEvent.KEYCODE_MEDIA_NEXT
        "PREVIOUS" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        "CHANNEL_UP" -> KeyEvent.KEYCODE_CHANNEL_UP
        "CHANNEL_DOWN" -> KeyEvent.KEYCODE_CHANNEL_DOWN
        "INFO" -> KeyEvent.KEYCODE_INFO
        "GUIDE" -> KeyEvent.KEYCODE_GUIDE
        "RED" -> KeyEvent.KEYCODE_PROG_RED
        "GREEN" -> KeyEvent.KEYCODE_PROG_GREEN
        "YELLOW" -> KeyEvent.KEYCODE_PROG_YELLOW
        "BLUE" -> KeyEvent.KEYCODE_PROG_BLUE
        else -> if (key.startsWith("DIGIT_")) {
            key.removePrefix("DIGIT_").toIntOrNull()?.let { KeyEvent.KEYCODE_0 + it }
        } else {
            null
        }
    }

    private fun JsonObject.string(name: String): String =
        (this[name] as? JsonPrimitive)?.content.orEmpty()

    private fun JsonObject.int(name: String): Int? =
        (this[name] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun JsonObject.long(name: String): Long? =
        (this[name] as? JsonPrimitive)?.content?.toLongOrNull()

    private fun JsonObject.bool(name: String): Boolean? =
        (this[name] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val MAX_PROFILES = 6
    }
}
