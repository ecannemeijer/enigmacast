package com.enigmacast.data.openwebif

import com.enigmacast.data.network.OkHttpClientFactory
import com.enigmacast.domain.model.BouquetChannelList
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ChannelGuide
import com.enigmacast.domain.model.ConnectionTestResult
import com.enigmacast.domain.model.EnigmaService
import com.enigmacast.domain.model.PowerStatus
import com.enigmacast.domain.model.ReceiverCapabilities
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.model.VolumeStatus
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
import com.enigmacast.util.EnigmaLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.Request
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

class OpenWebifException(message: String, val statusCode: Int? = null) : IOException(message)

@Singleton
class OpenWebifApi @Inject constructor(
    private val clientFactory: OkHttpClientFactory,
    private val endpoints: OpenWebifEndpoints,
) : OsdSource, RemoteControlSource, ServiceStateSource, ReceiverInfoSource {

    override suspend fun grab(config: ReceiverConfig, format: GrabFormat, mode: GrabMode): GrabbedImage {
        val url = endpoints.grab(config, format, mode)
        val started = System.currentTimeMillis()
        val response = execute(config, url, accept = "*/*")
        if (response.code !in 200..299) {
            throw OpenWebifException("Grab failed (${response.code})", response.code)
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
            throw OpenWebifException("Remote command failed (${response.code})", response.code)
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
            throw OpenWebifException("Zap mislukt (${response.code})", response.code)
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
            val channels = services(config, bouquet.reference)
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
        return services(config, bouquetReference).filter { BouquetChannels.isPlayableChannel(it.reference) }
    }

    override suspend fun currentService(config: ReceiverConfig): EnigmaService {
        val current = runCatching { get(config, endpoints.current(config)) }.getOrNull()
        val fromCurrent = current?.let { OpenWebifJson.parseService(it) }
        if (fromCurrent != null && fromCurrent.reference.isNotBlank()) return fromCurrent
        val status = get(config, endpoints.statusInfo(config))
        return OpenWebifJson.parseService(status)
    }

    override suspend fun capabilities(config: ReceiverConfig): ReceiverCapabilities {
        return probe(config).capabilities ?: throw OpenWebifException("OpenWebif did not return receiver info")
    }

    override suspend fun powerState(config: ReceiverConfig): PowerStatus {
        return OpenWebifJson.parsePower(get(config, endpoints.powerState(config)))
    }

    override suspend fun wake(config: ReceiverConfig) {
        get(config, endpoints.powerState(config, newState = 4))
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

    suspend fun testConnection(config: ReceiverConfig): ConnectionTestResult {
        return try {
            val probe = probe(config)
            val capabilities = probe.capabilities
                ?: return ConnectionTestResult(false, probe.failure ?: "OpenWebif is not available")
            val service = runCatching { currentService(config) }.getOrNull()
            val summary = buildString {
                append("Connected")
                if (capabilities.model.isNotBlank()) append("\nReceiver: ${capabilities.model}")
                if (capabilities.imageDistro.isNotBlank()) {
                    append("\nImage: ${capabilities.imageDistro} ${capabilities.imageVersion}".trim())
                }
                if (capabilities.enigmaVersion.isNotBlank()) append("\nEnigma: ${capabilities.enigmaVersion}")
                append("\nIP: ${config.host}")
                if (!config.useHttps) append("\nHTTP is not encrypted")
            }
            ConnectionTestResult(true, summary, capabilities, service)
        } catch (error: Exception) {
            ConnectionTestResult(false, error.message ?: "Connection failed")
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

    private suspend fun get(config: ReceiverConfig, url: String): String {
        val response = execute(config, url, accept = "application/json")
        if (response.code == 401) throw OpenWebifException("Authentication failed", 401)
        if (response.code !in 200..299) throw OpenWebifException("HTTP ${response.code}", response.code)
        return response.bytes.toString(Charsets.UTF_8)
    }

    /**
     * The body is read here, on [Dispatchers.IO]. Returning the open [okhttp3.Response]
     * and calling `body.bytes()` later runs that read on the caller, which is Main.
     */
    private suspend fun execute(config: ReceiverConfig, url: String, accept: String): HttpBytes =
        withContext(Dispatchers.IO) {
            val client = clientFactory.webifClient(config.allowInsecureCertificates, config.timeoutSeconds)
            val builder = Request.Builder().url(url).header("Accept", accept)
            if (config.username.isNotEmpty()) {
                builder.header("Authorization", Credentials.basic(config.username, config.password))
            }
            EnigmaLog.connection("request ${EnigmaLog.sanitizeUrl(url)}")
            client.newCall(builder.build()).execute().use { response ->
                HttpBytes(
                    code = response.code,
                    bytes = response.body.bytes(),
                    contentType = response.header("Content-Type"),
                )
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
