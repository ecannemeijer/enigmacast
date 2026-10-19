package com.enigmacast.data.openwebif

import com.enigmacast.R
import com.enigmacast.data.network.OkHttpClientFactory
import com.enigmacast.data.network.ReceiverRouter
import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.search.EpgSearchEvent
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
import com.enigmacast.data.settingsimport.FtpReceiverClient
import com.enigmacast.domain.service.BouquetChannels
import com.enigmacast.domain.service.BouquetOrder
import com.enigmacast.domain.settingsimport.SettingsFiles
import com.enigmacast.domain.source.OsdSource
import com.enigmacast.domain.source.ReceiverInfoSource
import com.enigmacast.domain.source.RemoteControlSource
import com.enigmacast.domain.source.ServiceStateSource
import com.enigmacast.domain.tuner.ChannelNavigator
import com.enigmacast.domain.tuner.NowEvent
import com.enigmacast.domain.tuner.TunerSnapshot
import com.enigmacast.util.EnigmaLog
import com.enigmacast.util.HasUiText
import com.enigmacast.util.UiText
import com.enigmacast.util.uiMessage
import kotlinx.coroutines.CancellationException
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
    private val dispatchers: com.enigmacast.util.AppDispatchers,
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
        val reference = parsed { OpenWebifJson.parseService(currentBody) }.reference
        if (reference.isBlank()) return parsed { OpenWebifJson.parseGuide(currentBody, """{"events":[]}""") }
        val epgBody = programmeBody(config, endpoints.epgService(config, reference))
        return parsed { OpenWebifJson.parseGuide(currentBody, epgBody) }
    }

    override suspend fun channelGuide(
        config: ReceiverConfig,
        serviceReference: String,
        channelName: String,
    ): ChannelGuide {
        val epgBody = programmeBody(config, endpoints.epgService(config, serviceReference))
        val currentBody = buildJsonObject {
            put("info", buildJsonObject {
                put("name", channelName)
                put("ref", serviceReference)
            })
        }.toString()
        return parsed { OpenWebifJson.parseGuide(currentBody, epgBody) }
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
        val service = parsed { OpenWebifJson.parseService(currentBody) }
        val hinted = OpenWebifJson.bouquetReference(currentBody)
        val television = televisionBouquets(config)
        val radio = radioBouquets(config)
        return bouquetContaining(config, television + radio, hinted, service.reference)
    }

    /**
     * The bouquet in [bouquets] that contains [serviceReference].
     * When the receiver has no current channel (standby, first connect), the hinted bouquet
     * is used if it has channels, otherwise the first bouquet that does. Television bouquets
     * come before radio, so that fallback is a TV bouquet unless only radio has channels.
     */
    private suspend fun bouquetContaining(
        config: ReceiverConfig,
        bouquets: List<BouquetEntry>,
        hinted: String?,
        serviceReference: String,
    ): BouquetChannelList {
        val hintedBouquet = bouquets.firstOrNull { bouquet ->
            hinted != null && bouquetIdentity(bouquet.reference) == bouquetIdentity(hinted)
        }
        val ordered = listOfNotNull(hintedBouquet) + bouquets.filter { it.reference != hintedBouquet?.reference }
        var fallback: BouquetChannelList? = null
        for (bouquet in ordered) {
            val channels = runCatching {
                numbered(config, bouquet.reference, services(config, bouquet.reference))
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                EnigmaLog.service("skip bouquet ${bouquet.name}: ${error.javaClass.simpleName}")
                emptyList()
            }.filter { BouquetChannels.isPlayableChannel(it.reference) }
            val list = BouquetChannelList(bouquet.name, bouquet.reference, channels)
            if (fallback == null && channels.isNotEmpty()) fallback = list
            if (channels.any { BouquetChannels.sameService(it.reference, serviceReference) }) return list
        }
        return fallback ?: BouquetChannelList("", "", emptyList())
    }

    override suspend fun televisionBouquets(config: ReceiverConfig): List<BouquetEntry> {
        return bouquets(config).filter { BouquetChannels.isTelevisionBouquet(it.reference) }
    }

    override suspend fun radioBouquets(config: ReceiverConfig): List<BouquetEntry> {
        return runCatching {
            parsed { OpenWebifJson.parseBouquets(get(config, endpoints.radioBouquets(config))) }
        }.getOrDefault(emptyList()).filter { BouquetChannels.isRadioBouquet(it.reference) }
    }

    override suspend fun channelsInBouquet(config: ReceiverConfig, bouquetReference: String): List<BouquetEntry> {
        return numbered(config, bouquetReference, services(config, bouquetReference))
            .filter { BouquetChannels.isPlayableChannel(it.reference) }
    }

    private var startPositions: Pair<String, Map<String, Int>>? = null
    private var startPositionsAtMs = 0L
    private var bouquetsTvText: TextStamp? = null

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
        val shifted = if (start == 0) {
            channels
        } else {
            channels.map { entry -> if (entry.number > 0) entry.copy(number = entry.number + start) else entry }
        }
        return ChannelNavigator.increasingNumbers(shifted)
    }

    override suspend fun currentService(config: ReceiverConfig): EnigmaService {
        val current = runCatching { get(config, endpoints.current(config)) }.getOrNull()
        val fromCurrent = current?.let { parsed { OpenWebifJson.parseService(it) } }
        if (fromCurrent != null && fromCurrent.reference.isNotBlank()) return fromCurrent
        val status = get(config, endpoints.statusInfo(config))
        return parsed { OpenWebifJson.parseService(status) }
    }

    override suspend fun capabilities(config: ReceiverConfig): ReceiverCapabilities {
        return probe(config).capabilities ?: throw OpenWebifException("OpenWebif did not return receiver info", uiText = UiText.res(R.string.error_no_receiver_info))
    }

    override suspend fun powerState(config: ReceiverConfig): PowerStatus {
        return parsed { OpenWebifJson.parsePower(get(config, endpoints.powerState(config))) }
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
        return parsed { OpenWebifJson.parseReceiverDetails(get(config, endpoints.about(config))) }
    }

    override suspend fun sleepTimer(config: ReceiverConfig): SleepTimerStatus {
        return parsed { OpenWebifJson.parseSleepTimer(get(config, endpoints.sleepTimer(config))) }
    }

    override suspend fun setSleepTimer(
        config: ReceiverConfig,
        minutes: Int,
        action: SleepTimerAction,
        enabled: Boolean,
    ): SleepTimerStatus {
        val body = get(config, endpoints.setSleepTimer(config, minutes, action, enabled))
        return parsed { OpenWebifJson.parseSleepTimer(body) }
    }

    override suspend fun volume(config: ReceiverConfig): VolumeStatus {
        return parsed { OpenWebifJson.parseVolume(get(config, endpoints.volume(config))) }
    }

    override suspend fun bouquets(config: ReceiverConfig): List<BouquetEntry> {
        // getservices of bouquets.tv is the list Enigma2 itself shows. /api/bouquets is not
        // always that order (stream bouquets first on some images).
        val root = """1:7:1:0:0:0:0:0:0:0:FROM BOUQUET "bouquets.tv" ORDER BY bouquet"""
        val fromBox = runCatching {
            parsed { OpenWebifJson.parseServices(get(config, endpoints.services(config, root, picons = false))) }
        }.getOrNull().orEmpty().filter { BouquetChannels.isTelevisionBouquet(it.reference) }
        val listed = fromBox.ifEmpty {
            runCatching { parsed { OpenWebifJson.parseBouquets(get(config, endpoints.bouquets(config))) } }.getOrDefault(emptyList())
        }
        val file = readBouquetsTv(config)
        val matched = if (file.isNullOrBlank()) 0 else BouquetOrder.matched(listed, file)
        if (file.isNullOrBlank() || matched == 0) {
            EnigmaLog.service("bouquet order unchanged: file=${!file.isNullOrBlank()} matched=$matched of ${listed.size}, first=${listed.firstOrNull()?.name}")
            return listed
        }
        val ordered = BouquetOrder.apply(listed, file)
        EnigmaLog.service("bouquet order from bouquets.tv ($matched): ${ordered.take(4).joinToString { it.name }}")
        return ordered
    }

    /**
     * bouquets.tv from the box. OpenWebif's `/file` answer is only accepted when it really
     * lists bouquets; a "forbidden" page used to count as success and the old order stayed.
     * FTP is the same route the settings download already uses to read /etc/enigma2.
     */
    internal suspend fun readBouquetsTv(config: ReceiverConfig): String? {
        val key = "${config.host}:${config.httpPort}:${config.ftpPort}"
        val now = System.currentTimeMillis()
        bouquetsTvText?.takeIf { it.key == key && now - it.atMs < 15_000 }?.let { return it.text }
        val path = "${SettingsFiles.ENIGMA_DIR}/bouquets.tv"
        val urls = LinkedHashSet<String>()
        urls += endpoints.recordedFile(config, path)
        endpoints.fileDownload(config, path).forEach { urls += it }
        for (url in urls) {
            val text = runCatching { fetchFile(config, url) }.getOrNull() ?: continue
            if (BouquetOrder.filesIn(text).isNotEmpty()) {
                bouquetsTvText = TextStamp(key, text, now)
                return text
            }
        }
        val ftp = withContext(Dispatchers.IO) {
            runCatching {
                FtpReceiverClient.open(config).use { it.download(path)?.toString(Charsets.UTF_8) }
            }.onFailure { EnigmaLog.service("bouquets.tv FTP failed: ${it.javaClass.simpleName}") }.getOrNull()
        }
        if (!ftp.isNullOrBlank() && BouquetOrder.filesIn(ftp).isNotEmpty()) {
            bouquetsTvText = TextStamp(key, ftp, now)
            return ftp
        }
        return null
    }

    override suspend fun services(config: ReceiverConfig, bouquetReference: String): List<BouquetEntry> {
        return try {
            parsed { OpenWebifJson.parseServices(get(config, endpoints.services(config, bouquetReference))) }
        } catch (error: OpenWebifException) {
            if (error.statusCode != 500) throw error
            EnigmaLog.service("channel list HTTP 500 with picons, retrying without")
            parsed { OpenWebifJson.parseServices(get(config, endpoints.services(config, bouquetReference, picons = false))) }
        }
    }

    /**
     * `/api/about` carries the tuner list and stream clients and is rebuilt on every call.
     * `/api/statusinfo` adds standby and the service on screen. The timer list is only
     * read when a tuner shows a recording, to learn which transponder it holds.
     */
    suspend fun tunerSnapshot(config: ReceiverConfig): TunerSnapshot {
        val about = get(config, endpoints.about(config))
        val status = runCatching { get(config, endpoints.statusInfo(config)) }.getOrNull()
        val first = parsed { OpenWebifJson.parseTunerSnapshot(about, status, null) }
        if (first.tuners.none { it.recording.isNotBlank() }) return first
        val timers = runCatching { get(config, endpoints.timerList(config)) }.getOrNull()
        return parsed { OpenWebifJson.parseTunerSnapshot(about, status, timers) }
    }

    suspend fun bouquetStartPositions(config: ReceiverConfig): Map<String, Int> {
        return parsed { OpenWebifJson.parseBouquetStartPositions(get(config, endpoints.bouquetStartPositions(config))) }
    }

    suspend fun picon(config: ReceiverConfig, path: String): ByteArray? {
        val response = execute(config, endpoints.picon(config, path), accept = "image/*")
        if (response.code !in 200..299 || response.bytes.isEmpty()) return null
        return response.bytes
    }

    suspend fun nowEvents(config: ReceiverConfig, bouquetReference: String): List<NowEvent> {
        return parsed { OpenWebifJson.parseNowEvents(programmeBody(config, endpoints.epgNow(config, bouquetReference))) }
    }

    /** `/api/about` alone (model, image, transcoding), without the grab and stream port probes. */
    suspend fun about(config: ReceiverConfig): ReceiverCapabilities = parsed { OpenWebifJson.parseAbout(
        get(config, endpoints.about(config)),
        https = config.useHttps,
        streamingAvailable = false,
        grabs = GrabSupport(png = false, jpg = false, osd = false, all = false, authenticationRequired = false),
    ) }

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

    suspend fun epgSearch(config: ReceiverConfig, query: String): List<EpgSearchEvent> {
        return parsed { OpenWebifJson.parseEpgSearch(get(config, endpoints.epgSearch(config, query))) }
    }

    suspend fun serviceEvents(config: ReceiverConfig, serviceReference: String): List<GuideEvent> {
        return parsed { OpenWebifJson.parseGuideEvents(programmeBody(config, endpoints.epgService(config, serviceReference)), serviceReference) }
    }

    suspend fun bouquetEvents(config: ReceiverConfig, bouquetReference: String, startSec: Long, minutes: Int): List<GuideEvent> {
        return parsed { OpenWebifJson.parseGuideEvents(programmeBody(config, endpoints.epgMulti(config, bouquetReference, startSec, minutes))) }
    }

    suspend fun addTimer(config: ReceiverConfig, serviceReference: String, eventId: Int): TimerResult {
        return parsed { OpenWebifJson.parseTimerResult(get(config, endpoints.timerAddByEventId(config, serviceReference, eventId))) }
    }

    suspend fun addTimer(config: ReceiverConfig, serviceReference: String, begin: Long, end: Long, name: String, description: String): TimerResult {
        return parsed { OpenWebifJson.parseTimerResult(get(config, endpoints.timerAdd(config, serviceReference, begin, end, name, description))) }
    }

    internal suspend fun timers(config: ReceiverConfig): List<OpenWebifJson.ReceiverTimer> {
        return parsed { OpenWebifJson.parseTimers(get(config, endpoints.timerList(config))) }
    }

    /** Null when the AutoTimer plugin is not installed (or answers no list). */
    suspend fun autoTimers(config: ReceiverConfig): List<AutoTimerEntry>? {
        val response = runCatching { execute(config, endpoints.autoTimerList(config), accept = "application/xml") }.getOrNull() ?: return null
        if (response.code !in 200..299) return null
        val body = response.bytes.toString(Charsets.UTF_8)
        if (!body.contains("<autotimer", ignoreCase = true)) return null
        return parsed { AutoTimerXml.parse(body) }
    }

    /** Adds an AutoTimer and returns its id (looked up by name afterwards), or null when it failed. */
    suspend fun addAutoTimer(config: ReceiverConfig, name: String, match: String, serviceReference: String): String? {
        val body = runCatching { get(config, endpoints.autoTimerAdd(config, name, match, serviceReference)) }.getOrNull() ?: return null
        if (!AutoTimerXml.success(body)) return null
        val id = autoTimers(config)?.lastOrNull { it.name == name && it.match.equals(match, ignoreCase = true) }?.id
        runCatching { get(config, endpoints.autoTimerParse(config)) }
        return id ?: ""
    }

    suspend fun removeAutoTimer(config: ReceiverConfig, id: String): Boolean =
        runCatching { AutoTimerXml.success(get(config, endpoints.autoTimerRemove(config, id))) }.getOrDefault(false)

    suspend fun deleteTimer(config: ReceiverConfig, serviceReference: String, begin: Long, end: Long) {
        get(config, endpoints.timerDelete(config, serviceReference, begin, end))
    }

    internal suspend fun movies(config: ReceiverConfig): List<OpenWebifJson.ReceiverMovie> {
        return parsed { OpenWebifJson.parseMovies(get(config, endpoints.movieList(config))) }
    }

    suspend fun deleteMovie(config: ReceiverConfig, serviceReference: String) {
        get(config, endpoints.movieDelete(config, serviceReference))
    }

    suspend fun testConnection(config: ReceiverConfig): ConnectionTestResult {
        return try {
            val probe = probe(config)
            val capabilities = probe.capabilities
                ?: return ConnectionTestResult(
                    false,
                    probe.failure?.let(UiText::Raw) ?: UiText.res(R.string.conn_openwebif_unavailable),
                    authFailed = probe.authFailed,
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
            return Probe(null, error.message, authFailed = error.statusCode == 401)
        }
        val pngOsd = probeGrab(config, GrabFormat.PNG, GrabMode.OSD)
        val jpgOsd = probeGrab(config, GrabFormat.JPG, GrabMode.OSD)
        val pngAll = probeGrab(config, GrabFormat.PNG, GrabMode.ALL)
        val jpgAll = probeGrab(config, GrabFormat.JPG, GrabMode.ALL)
        val streaming = probeStreamPort(config)
        val capabilities = parsed { OpenWebifJson.parseAbout(
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
        ) }
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

    /**
     * Programme endpoints answer HTTP 500 when the receiver has no EPG for that service.
     * That is an empty guide, not a failure to show on screen.
     */
    private suspend fun programmeBody(config: ReceiverConfig, url: String): String = try {
        get(config, url)
    } catch (error: OpenWebifException) {
        if (error.statusCode != 404 && error.statusCode != 500) throw error
        EnigmaLog.service("no programme data (HTTP ${error.statusCode})")
        """{"events":[]}"""
    }

    /**
     * A text file on the receiver through OpenWebif `/file?file=` (read-only), or null when it
     * does not exist: OpenWebif answers 200 with "File '<path>' not found" then.
     */
    suspend fun receiverTextFile(config: ReceiverConfig, path: String): String? {
        val response = execute(config, endpoints.recordedFile(config, path), accept = "*/*")
        if (response.code == 404) return null
        if (response.code == 401) throw OpenWebifException("Authentication failed", 401, UiText.res(R.string.error_auth_failed))
        if (response.code !in 200..299) throw OpenWebifException("HTTP ${response.code}", response.code)
        val text = response.bytes.toString(Charsets.UTF_8)
        return text.takeUnless { OpenWebifFiles.isNotFound(text, path) }
    }

    /**
     * The same file, trying every OpenWebif download route. Some images only answer
     * `/file?action=download`, and `/file?file=` then comes back empty or as "not found".
     * Null when none of the routes returns the file. Authentication failures still throw.
     */
    suspend fun receiverFile(config: ReceiverConfig, path: String): String? {
        val urls = LinkedHashSet<String>()
        urls += endpoints.recordedFile(config, path)
        endpoints.fileDownload(config, path).forEach { urls += it }
        for (url in urls) {
            try {
                val response = execute(config, url, accept = "*/*")
                if (response.code == 401) throw OpenWebifException("Authentication failed", 401, UiText.res(R.string.error_auth_failed))
                if (response.code !in 200..299) continue
                val text = response.bytes.toString(Charsets.UTF_8)
                if (OpenWebifFiles.isReadable(text, path)) return text
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: OpenWebifException) {
                if (error.statusCode == 401) throw error
            } catch (error: Exception) {
                EnigmaLog.service("file $path failed: ${error.javaClass.simpleName}")
            }
        }
        return null
    }

    private suspend fun fetchFile(config: ReceiverConfig, url: String): String? {
        val response = execute(config, url, accept = "*/*")
        if (response.code !in 200..299) return null
        return response.bytes.toString(Charsets.UTF_8)
    }

    /**
     * Parsing (EPG, bouquets, services, timers, movies) runs on the default dispatcher: the
     * callers include Main-bound view models and sessions, and an epgmulti/epgservice answer can
     * be megabytes. The request inside the block still suspends on OkHttp, not on a thread.
     */
    private suspend fun <T> parsed(block: suspend () -> T): T = withContext(dispatchers.default) { block() }

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
        val authFailed: Boolean = false,
    )

    private data class TextStamp(val key: String, val text: String, val atMs: Long)
}

/** OpenWebif's answer for a missing file in `/file?file=`. */
object OpenWebifFiles {
    fun isNotFound(body: String, path: String): Boolean {
        val head = body.trimStart().take(path.length + 40)
        return head.startsWith("File ") && head.contains(path) && head.contains("not found", ignoreCase = true)
    }

    /** A body that is the file itself: not the "not found" text and not an HTML error page. */
    fun isReadable(body: String, path: String): Boolean {
        if (body.isBlank() || isNotFound(body, path)) return false
        val head = body.trimStart().take(15).lowercase()
        return !head.startsWith("<!doctype") && !head.startsWith("<html")
    }
}
