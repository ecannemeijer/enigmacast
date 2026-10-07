package com.enigmacast.data.openwebif

import com.enigmacast.R
import com.enigmacast.data.network.OkHttpClientFactory
import com.enigmacast.data.network.ReceiverRouter
import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.epg.TimerResult
import com.enigmacast.domain.model.BouquetChannelList
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ChannelGuide
import com.enigmacast.domain.model.ConnectionTestResult
import com.enigmacast.domain.model.EnigmaService
import com.enigmacast.domain.model.PowerAction
import com.enigmacast.domain.model.PowerStatus
import com.enigmacast.domain.model.ReceiverCapabilities
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.model.ReceiverDetails
import com.enigmacast.domain.model.SleepTimerAction
import com.enigmacast.domain.model.SleepTimerStatus
import com.enigmacast.domain.model.VolumeStatus
import com.enigmacast.domain.network.TranscodeDetection
import com.enigmacast.domain.network.TranscodeUrls
import com.enigmacast.domain.osd.GrabFormat
import com.enigmacast.domain.osd.GrabMode
import com.enigmacast.domain.osd.GrabbedImage
import com.enigmacast.domain.osd.PngInspector
import com.enigmacast.domain.remote.EnigmaKey
import com.enigmacast.domain.remote.KeyAction
import com.enigmacast.domain.service.BouquetChannels
import com.enigmacast.domain.source.OsdSource
import com.enigmacast.domain.source.ReceiverInfoSource
import com.enigmacast.domain.source.RemoteControlSource
import com.enigmacast.domain.source.ServiceStateSource
import com.enigmacast.domain.tuner.NowEvent
import com.enigmacast.domain.tuner.TunerSnapshot
import com.enigmacast.util.EnigmaLog
import com.enigmacast.util.HasUiText
import com.enigmacast.util.UiText
import com.enigmacast.util.uiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/** [uiText] is what the screen shows; app-made failures are translated, others pass the raw message. */
class OpenWebifException(
    message: String,
    val statusCode: Int? = null,
    override val uiText: UiText = UiText.Raw(message),
) : IOException(message), HasUiText

@Singleton
class OpenWebifApi @Inject constructor(
    private val clientFactory: OkHttpClientFactory,
    private val endpoints: OpenWebifEndpoints,
    private val router: ReceiverRouter,
) : OsdSource, RemoteControlSource, ServiceStateSource, ReceiverInfoSource {

    override suspend fun grab(config: ReceiverConfig, format: GrabFormat, mode: GrabMode): GrabbedImage {
        val url = endpoints.grab(config, format, mode)
        val started = System.currentTimeMillis()
        val response = execute(config, url, accept = "*/*")
        if (response.code !in 200..299) {
            throw OpenWebifException("Grab failed (${response.code})", response.code, UiText.res(R.string.error_grab_failed, response.code))
        }
        return GrabbedImage(
            bytes = response.bytes,
            contentType = response.contentType,
            httpStatus = response.code,
            durationMs = System.currentTimeMillis() - started,
        )
    }

    override suspend fun sendKey(config: ReceiverConfig, key: EnigmaKey, action: KeyAction) {
        val url = endpoints.remoteControl(config, key, action)
        val response = execute(config, url, accept = "application/json")
        val body = response.bytes.toString(Charsets.UTF_8)
        if (response.code !in 200..299 || !OpenWebifJson.isSuccess(body)) {
            throw OpenWebifException("Remote command failed (${response.code})", response.code, UiText.res(R.string.error_remote_failed, response.code))
        }
        EnigmaLog.remote("${key.name} ${action.name}")
    }

    override suspend fun channelGuide(config: ReceiverConfig): ChannelGuide {
        val currentBody = get(config, endpoints.current(config))
        val reference = OpenWebifJson.parseService(currentBody).reference
        if (reference.isBlank()) return OpenWebifJson.parseGuide(currentBody, """{"events":[]}""")
        val epgBody = get(config, endpoints.epgService(config, reference))
        return OpenWebifJson.parseGuide(currentBody, epgBody)
    }

    override suspend fun channelGuide(
        config: ReceiverConfig,
        serviceReference: String,
        channelName: String,
    ): ChannelGuide {
        val epgBody = get(config, endpoints.epgService(config, serviceReference))
        val currentBody = buildJsonObject {
            put("info", buildJsonObject {
                put("name", channelName)
                put("ref", serviceReference)
            })
        }.toString()
        return OpenWebifJson.parseGuide(currentBody, epgBody)
    }

    override suspend fun zap(config: ReceiverConfig, serviceReference: String) {
        val response = execute(config, endpoints.zap(config, serviceReference), accept = "application/json")
        val body = response.bytes.toString(Charsets.UTF_8)
        if (response.code !in 200..299 || !OpenWebifJson.isSuccess(body)) {
            throw OpenWebifException("Zap failed (${response.code})", response.code, UiText.res(R.string.error_zap_failed, response.code))
        }
    }

    override suspend fun currentBouquetChannels(config: ReceiverConfig): BouquetChannelList {
        val currentBody = get(config, endpoints.current(config))
        val service = OpenWebifJson.parseService(currentBody)
        val hinted = OpenWebifJson.bouquetReference(currentBody)
        val bouquets = bouquets(config).filter { BouquetChannels.isTelevisionBouquet(it.reference) }
        val hintedBouquet = bouquets.firstOrNull { bouquet ->
            hinted != null && bouquetIdentity(bouquet.reference) == bouquetIdentity(hinted)
        }
        val ordered = listOfNotNull(hintedBouquet) + bouquets.filter { it.reference != hintedBouquet?.reference }
        var fallback: BouquetChannelList? = null
        for (bouquet in ordered) {
            val channels = numbered(config, bouquet.reference, services(config, bouquet.reference))
                .filter { BouquetChannels.isPlayableChannel(it.reference) }
            val list = BouquetChannelList(bouquet.name, bouquet.reference, channels)
            if (fallback == null && channels.isNotEmpty()) fallback = list
            if (channels.any { BouquetChannels.sameService(it.reference, service.reference) }) return list
        }
        return fallback ?: BouquetChannelList("", "", emptyList())
    }

    override suspend fun televisionBouquets(config: ReceiverConfig): List<BouquetEntry> {
        return bouquets(config).filter { BouquetChannels.isTelevisionBouquet(it.reference) }
    }

    override suspend fun channelsInBouquet(config: ReceiverConfig, bouquetReference: String): List<BouquetEntry> {
        return numbered(config, bouquetReference, services(config, bouquetReference))
            .filter { BouquetChannels.isPlayableChannel(it.reference) }
    }

    private var startPositions: Pair<String, Map<String, Int>>? = null
    private var startPositionsAtMs = 0L

    /**
     * Turns positions within a bouquet into the numbers the receiver shows, by adding the
     * number of channels in the bouquets before it. Images without `startpos` keep the
     * position within the bouquet.
     */
    private suspend fun numbered(
        config: ReceiverConfig,
        bouquetReference: String,
        channels: List<BouquetEntry>,
    ): List<BouquetEntry> {
        val key = "${config.host}:${config.httpPort}"
        val cached = startPositions
        val starts = if (cached != null && cached.first == key && System.currentTimeMillis() - startPositionsAtMs < 600_000) {
            cached.second
        } else {
            val loaded = runCatching { bouquetStartPositions(config) }.getOrDefault(emptyMap())
                .mapKeys { bouquetIdentity(it.key) }
            startPositions = key to loaded
            startPositionsAtMs = System.currentTimeMillis()
            loaded
        }
        val start = starts[bouquetIdentity(bouquetReference)] ?: 0
        if (start == 0) return channels
        return channels.map { entry -> if (entry.number > 0) entry.copy(number = entry.number + start) else entry }
    }

    override suspend fun currentService(config: ReceiverConfig): EnigmaService {
        val current = runCatching { get(config, endpoints.current(config)) }.getOrNull()
        val fromCurrent = current?.let { OpenWebifJson.parseService(it) }
        if (fromCurrent != null && fromCurrent.reference.isNotBlank()) return fromCurrent
        val status = get(config, endpoints.statusInfo(config))
        return OpenWebifJson.parseService(status)
    }

    override suspend fun capabilities(config: ReceiverConfig): ReceiverCapabilities {
        return probe(config).capabilities ?: throw OpenWebifException("OpenWebif did not return receiver info", uiText = UiText.res(R.string.error_no_receiver_info))
    }

    override suspend fun powerState(config: ReceiverConfig): PowerStatus {
        return OpenWebifJson.parsePower(get(config, endpoints.powerState(config)))
    }

    override suspend fun wake(config: ReceiverConfig) {
        setPowerState(config, PowerAction.WAKE_UP)
    }

    override suspend fun setPowerState(config: ReceiverConfig, action: PowerAction) {
        val body = get(config, endpoints.powerState(config, action))
        if (!OpenWebifJson.isSuccess(body)) {
            throw OpenWebifException("Receiver refused the command", uiText = UiText.res(R.string.error_command_refused))
        }
        EnigmaLog.connection("power ${action.name}")
    }

    override suspend fun details(config: ReceiverConfig): ReceiverDetails {
        return OpenWebifJson.parseReceiverDetails(get(config, endpoints.about(config)))
    }

    override suspend fun sleepTimer(config: ReceiverConfig): SleepTimerStatus {
        return OpenWebifJson.parseSleepTimer(get(config, endpoints.sleepTimer(config)))
    }

    override suspend fun setSleepTimer(
        config: ReceiverConfig,
        minutes: Int,
        action: SleepTimerAction,
        enabled: Boolean,
    ): SleepTimerStatus {
        val body = get(config, endpoints.setSleepTimer(config, minutes, action, enabled))
        return OpenWebifJson.parseSleepTimer(body)
    }

    override suspend fun volume(config: ReceiverConfig): VolumeStatus {
        return OpenWebifJson.parseVolume(get(config, endpoints.volume(config)))
    }

    override suspend fun bouquets(config: ReceiverConfig): List<BouquetEntry> {
        return OpenWebifJson.parseBouquets(get(config, endpoints.bouquets(config)))
    }

    override suspend fun services(config: ReceiverConfig, bouquetReference: String): List<BouquetEntry> {
        return OpenWebifJson.parseServices(get(config, endpoints.services(config, bouquetReference)))
    }

    /**
     * `/api/about` carries the tuner list and stream clients and is rebuilt on every call.
     * `/api/statusinfo` adds standby and the service on screen. The timer list is only
     * read when a tuner shows a recording, to learn which transponder it holds.
     */
    suspend fun tunerSnapshot(config: ReceiverConfig): TunerSnapshot {
        val about = get(config, endpoints.about(config))
        val status = runCatching { get(config, endpoints.statusInfo(config)) }.getOrNull()
        val first = OpenWebifJson.parseTunerSnapshot(about, status, null)
        if (first.tuners.none { it.recording.isNotBlank() }) return first
        val timers = runCatching { get(config, endpoints.timerList(config)) }.getOrNull()
        return OpenWebifJson.parseTunerSnapshot(about, status, timers)
    }

    suspend fun bouquetStartPositions(config: ReceiverConfig): Map<String, Int> {
        return OpenWebifJson.parseBouquetStartPositions(get(config, endpoints.bouquetStartPositions(config)))
    }

    suspend fun picon(config: ReceiverConfig, path: String): ByteArray? {
        val response = execute(config, endpoints.picon(config, path), accept = "image/*")
        if (response.code !in 200..299 || response.bytes.isEmpty()) return null
        return response.bytes
    }

    suspend fun nowEvents(config: ReceiverConfig, bouquetReference: String): List<NowEvent> {
        return OpenWebifJson.parseNowEvents(get(config, endpoints.epgNow(config, bouquetReference)))
    }

    /** `/api/about` alone (model, image, transcoding), without the grab and stream port probes. */
    suspend fun about(config: ReceiverConfig): ReceiverCapabilities = OpenWebifJson.parseAbout(
        get(config, endpoints.about(config)),
        https = config.useHttps,
        streamingAvailable = false,
        grabs = GrabSupport(png = false, jpg = false, osd = false, all = false, authenticationRequired = false),
    )

    /**
     * Asks OpenWebif where this receiver transcodes ([TranscodeUrls.detect]); null when the
     * answer has no stream URL. The playlist may contain the stream password, so it is never logged.
     */
    suspend fun transcodeDetection(config: ReceiverConfig): TranscodeDetection? {
        val response = execute(config, endpoints.phonePlaylist(config), accept = "*/*")
        if (response.code == 401) throw OpenWebifException("Authentication failed", 401, UiText.res(R.string.error_auth_failed))
        if (response.code !in 200..299) throw OpenWebifException("HTTP ${response.code}", response.code)
        return TranscodeUrls.detect(response.bytes.toString(Charsets.UTF_8))
    }

    suspend fun serviceEvents(config: ReceiverConfig, serviceReference: String): List<GuideEvent> {
        return OpenWebifJson.parseGuideEvents(get(config, endpoints.epgService(config, serviceReference)), serviceReference)
    }

    suspend fun bouquetEvents(config: ReceiverConfig, bouquetReference: String, startSec: Long, minutes: Int): List<GuideEvent> {
        return OpenWebifJson.parseGuideEvents(get(config, endpoints.epgMulti(config, bouquetReference, startSec, minutes)))
    }

    suspend fun addTimer(config: ReceiverConfig, serviceReference: String, eventId: Int): TimerResult {
        return OpenWebifJson.parseTimerResult(get(config, endpoints.timerAddByEventId(config, serviceReference, eventId)))
    }

    suspend fun testConnection(config: ReceiverConfig): ConnectionTestResult {
        return try {
            val probe = probe(config)
            val capabilities = probe.capabilities
                ?: return ConnectionTestResult(
                    false,
                    probe.failure?.let(UiText::Raw) ?: UiText.res(R.string.conn_openwebif_unavailable),
                )
            val service = runCatching { currentService(config) }.getOrNull()
            val summary = buildList {
                add(UiText.res(R.string.conn_connected))
                if (capabilities.model.isNotBlank()) add(UiText.res(R.string.conn_receiver_line, capabilities.model))
                if (capabilities.imageDistro.isNotBlank()) {
                    add(UiText.res(R.string.conn_image_line, "${capabilities.imageDistro} ${capabilities.imageVersion}".trim()))
                }
                if (capabilities.enigmaVersion.isNotBlank()) add(UiText.res(R.string.conn_enigma_line, capabilities.enigmaVersion))
                add(UiText.res(R.string.conn_ip_line, config.host))
                if (!config.useHttps) add(UiText.res(R.string.conn_http_unencrypted))
            }
            ConnectionTestResult(true, UiText.Joined(summary), capabilities, service)
        } catch (error: Exception) {
            ConnectionTestResult(false, error.uiMessage(UiText.res(R.string.conn_failed)))
        }
    }

    private fun bouquetIdentity(reference: String): String {
        val decoded = reference.replace("%22", "\"").replace("&quot;", "\"")
        val marker = "FROM BOUQUET"
        val index = decoded.indexOf(marker, ignoreCase = true)
        return if (index >= 0) decoded.substring(index) else decoded
    }

    private suspend fun probe(config: ReceiverConfig): Probe {
        var authRequired = false
        val aboutBody = try {
            get(config, endpoints.about(config))
        } catch (error: OpenWebifException) {
            if (error.statusCode == 401) authRequired = true
            return Probe(null, error.message)
        }
        val pngOsd = probeGrab(config, GrabFormat.PNG, GrabMode.OSD)
        val jpgOsd = probeGrab(config, GrabFormat.JPG, GrabMode.OSD)
        val pngAll = probeGrab(config, GrabFormat.PNG, GrabMode.ALL)
        val jpgAll = probeGrab(config, GrabFormat.JPG, GrabMode.ALL)
        val streaming = probeStreamPort(config)
        val capabilities = OpenWebifJson.parseAbout(
            body = aboutBody,
            https = config.useHttps,
            streamingAvailable = streaming,
            grabs = GrabSupport(
                png = pngOsd || pngAll,
                jpg = jpgOsd || jpgAll,
                osd = pngOsd || jpgOsd,
                all = pngAll || jpgAll,
                authenticationRequired = authRequired || config.username.isNotEmpty(),
            ),
        )
        return Probe(capabilities, null)
    }

    private suspend fun probeGrab(config: ReceiverConfig, format: GrabFormat, mode: GrabMode): Boolean {
        return try {
            val image = grab(config, format, mode)
            image.httpStatus == 200 && (PngInspector.isPng(image.bytes) || PngInspector.isJpeg(image.bytes))
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun probeStreamPort(config: ReceiverConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(config.host, config.streamingPort), 2_000)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** JSON body of [url]; throws [OpenWebifException] with the status code for non-2xx answers. */
    internal suspend fun body(config: ReceiverConfig, url: String): String = get(config, url)

    private suspend fun get(config: ReceiverConfig, url: String): String {
        val response = execute(config, url, accept = "application/json")
        if (response.code == 401) throw OpenWebifException("Authentication failed", 401, UiText.res(R.string.error_auth_failed))
        if (response.code !in 200..299) throw OpenWebifException("HTTP ${response.code}", response.code)
        return response.bytes.toString(Charsets.UTF_8)
    }

    /**
     * The body is read on OkHttp's dispatcher, not on Main.
     * Cancelling the coroutine cancels the call, so a stale zap or poll cannot
     * finish and then drive playback.
     */
    private suspend fun execute(config: ReceiverConfig, url: String, accept: String): HttpBytes {
        val client = clientFactory.webifClient(config.allowInsecureCertificates, config.timeoutSeconds)
        // URLs are built for the home address; away from home they go to the remote profile.
        val target = router.rewrite(config, url)
        val builder = Request.Builder().url(target).header("Accept", accept)
        if (config.username.isNotEmpty()) {
            builder.header("Authorization", Credentials.basic(config.username, config.password))
        }
        EnigmaLog.connection("request ${EnigmaLog.sanitizeUrl(url)}${if (target != url) " (remote)" else ""}")
        return try {
            client.newCall(builder.build()).awaitBytes()
        } catch (error: IOException) {
            if (error !is OpenWebifException) router.onRequestFailed(config)
            throw error
        }
    }

    private suspend fun Call.awaitBytes(): HttpBytes = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                resumeOnce(continuation, Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use { open ->
                        Result.success(
                            HttpBytes(
                                code = open.code,
                                bytes = open.body.bytes(),
                                contentType = open.header("Content-Type"),
                            ),
                        )
                    }
                } catch (error: Exception) {
                    val failure = error as? IOException ?: IOException(error)
                    Result.failure(failure)
                }
                resumeOnce(continuation, result)
            }
        })
    }

    private fun resumeOnce(
        continuation: kotlinx.coroutines.CancellableContinuation<HttpBytes>,
        result: Result<HttpBytes>,
    ) {
        try {
            if (continuation.isActive) continuation.resumeWith(result)
        } catch (_: IllegalStateException) {
            // The coroutine was cancelled while this callback was resuming.
        }
    }

    private data class HttpBytes(
        val code: Int,
        val bytes: ByteArray,
        val contentType: String?,
    )

    private data class Probe(
        val capabilities: ReceiverCapabilities?,
        val failure: String?,
    )
}
