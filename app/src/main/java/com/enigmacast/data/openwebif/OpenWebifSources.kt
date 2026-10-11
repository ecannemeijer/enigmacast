package com.enigmacast.data.openwebif

import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.epg.TimerResult
import com.enigmacast.domain.model.BouquetChannelList
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ChannelGuide
import com.enigmacast.domain.model.EnigmaService
import com.enigmacast.domain.model.PowerAction
import com.enigmacast.domain.model.PowerStatus
import com.enigmacast.domain.model.ReceiverCapabilities
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.model.ReceiverDetails
import com.enigmacast.domain.model.SleepTimerAction
import com.enigmacast.domain.model.SleepTimerStatus
import com.enigmacast.domain.model.VolumeStatus
import com.enigmacast.domain.osd.GrabFormat
import com.enigmacast.domain.osd.GrabMode
import com.enigmacast.domain.osd.GrabbedImage
import com.enigmacast.domain.remote.EnigmaKey
import com.enigmacast.domain.remote.KeyAction
import com.enigmacast.domain.osdinfo.FrontendSignal
import com.enigmacast.domain.osdinfo.ReceiverCurrent
import com.enigmacast.domain.osdinfo.SignalReading
import com.enigmacast.domain.osdinfo.StreamClientInfo
import com.enigmacast.domain.source.EpgSource
import com.enigmacast.domain.source.OsdInfoSource
import com.enigmacast.domain.source.OsdSource
import com.enigmacast.domain.source.ReceiverInfoSource
import com.enigmacast.domain.source.RemoteControlSource
import com.enigmacast.domain.source.ServiceStateSource
import com.enigmacast.domain.source.TunerStatusSource
import com.enigmacast.domain.tuner.NowEvent
import com.enigmacast.domain.tuner.TunerSnapshot
import com.enigmacast.util.EnigmaLog
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpenWebifOsdSource @Inject constructor(
    private val api: OpenWebifApi,
) : OsdSource {
    override suspend fun grab(config: ReceiverConfig, format: GrabFormat, mode: GrabMode): GrabbedImage {
        return api.grab(config, format, mode)
    }
}

@Singleton
class OpenWebifRemoteControlSource @Inject constructor(
    private val api: OpenWebifApi,
) : RemoteControlSource {
    override suspend fun sendKey(config: ReceiverConfig, key: EnigmaKey, action: KeyAction) {
        api.sendKey(config, key, action)
    }
}

@Singleton
class OpenWebifServiceStateSource @Inject constructor(
    private val api: OpenWebifApi,
) : ServiceStateSource {
    override suspend fun currentService(config: ReceiverConfig): EnigmaService {
        return api.currentService(config)
    }

    override suspend fun channelGuide(config: ReceiverConfig): ChannelGuide {
        return api.channelGuide(config)
    }

    override suspend fun channelGuide(
        config: ReceiverConfig,
        serviceReference: String,
        channelName: String,
    ): ChannelGuide {
        return api.channelGuide(config, serviceReference, channelName)
    }

    override suspend fun zap(config: ReceiverConfig, serviceReference: String) {
        api.zap(config, serviceReference)
    }

    override suspend fun currentBouquetChannels(config: ReceiverConfig): BouquetChannelList {
        return api.currentBouquetChannels(config)
    }

    override suspend fun televisionBouquets(config: ReceiverConfig): List<BouquetEntry> {
        return api.televisionBouquets(config)
    }

    override suspend fun radioBouquets(config: ReceiverConfig): List<BouquetEntry> {
        return api.radioBouquets(config)
    }

    override suspend fun channelsInBouquet(config: ReceiverConfig, bouquetReference: String): List<BouquetEntry> {
        return api.channelsInBouquet(config, bouquetReference)
    }
}

@Singleton
class OpenWebifReceiverInfoSource @Inject constructor(
    private val api: OpenWebifApi,
) : ReceiverInfoSource {
    override suspend fun capabilities(config: ReceiverConfig): ReceiverCapabilities = api.capabilities(config)
    override suspend fun powerState(config: ReceiverConfig): PowerStatus = api.powerState(config)
    override suspend fun wake(config: ReceiverConfig) = api.wake(config)
    override suspend fun setPowerState(config: ReceiverConfig, action: PowerAction) = api.setPowerState(config, action)
    override suspend fun details(config: ReceiverConfig): ReceiverDetails = api.details(config)
    override suspend fun sleepTimer(config: ReceiverConfig): SleepTimerStatus = api.sleepTimer(config)
    override suspend fun setSleepTimer(
        config: ReceiverConfig,
        minutes: Int,
        action: SleepTimerAction,
        enabled: Boolean,
    ): SleepTimerStatus = api.setSleepTimer(config, minutes, action, enabled)
    override suspend fun volume(config: ReceiverConfig): VolumeStatus = api.volume(config)
    override suspend fun bouquets(config: ReceiverConfig): List<BouquetEntry> = api.bouquets(config)
    override suspend fun services(config: ReceiverConfig, bouquetReference: String): List<BouquetEntry> {
        return api.services(config, bouquetReference)
    }
}

@Singleton
class OpenWebifTunerStatusSource @Inject constructor(
    private val api: OpenWebifApi,
) : TunerStatusSource {
    override suspend fun snapshot(config: ReceiverConfig): TunerSnapshot = api.tunerSnapshot(config)
    override suspend fun bouquetStartPositions(config: ReceiverConfig): Map<String, Int> = api.bouquetStartPositions(config)
    override suspend fun picon(config: ReceiverConfig, path: String): ByteArray? = api.picon(config, path)
    override suspend fun nowEvents(config: ReceiverConfig, bouquetReference: String): List<NowEvent> =
        api.nowEvents(config, bouquetReference)
}

@Singleton
class OpenWebifEpgSource @Inject constructor(
    private val api: OpenWebifApi,
) : EpgSource {
    override suspend fun serviceEvents(config: ReceiverConfig, serviceReference: String): List<GuideEvent> =
        api.serviceEvents(config, serviceReference)

    override suspend fun bouquetEvents(
        config: ReceiverConfig,
        bouquetReference: String,
        startSec: Long,
        minutes: Int,
    ): List<GuideEvent> = api.bouquetEvents(config, bouquetReference, startSec, minutes)

    override suspend fun record(config: ReceiverConfig, serviceReference: String, eventId: Int): TimerResult =
        api.addTimer(config, serviceReference, eventId)

    override suspend fun search(config: ReceiverConfig, query: String) = api.epgSearch(config, query)
}

@Singleton
class OpenWebifOsdInfoSource @Inject constructor(
    private val api: OpenWebifApi,
    private val endpoints: OpenWebifEndpoints,
) : OsdInfoSource {
    /** Per receiver: the signal URL that answered, or "" when neither exists. */
    private val signalUrl = ConcurrentHashMap<String, String>()

    /** Per receiver: false once the frontend files are forbidden. */
    private val frontendFiles = ConcurrentHashMap<String, Boolean>()

    /** `receiver/tuner/name` keys that answered 404, so the banner poll does not ask again. */
    private val missingProc = ConcurrentHashMap.newKeySet<String>()

    override suspend fun signal(config: ReceiverConfig): SignalReading? {
        val key = "${config.id}@${config.host}:${config.httpPort}"
        val known = signalUrl[key]
        if (known == "") return null
        val candidates = if (known != null) listOf(known) else endpoints.signalPaths(config)
        for (url in candidates) {
            try {
                val reading = OsdInfoJson.parseSignal(api.body(config, url))
                if (known == null) {
                    signalUrl[key] = url
                    EnigmaLog.osdInfo("signal endpoint ${url.substringAfter("/api/")}")
                }
                return reading
            } catch (error: OpenWebifException) {
                if (error.statusCode != 404) throw error
            }
        }
        signalUrl[key] = ""
        EnigmaLog.osdInfo("no signal endpoint (tunersignal and signal both 404)")
        return null
    }

    override suspend fun current(config: ReceiverConfig): ReceiverCurrent =
        OsdInfoJson.parseCurrent(api.body(config, endpoints.current(config)))

    override suspend fun streamClients(config: ReceiverConfig): List<StreamClientInfo> =
        OsdInfoJson.parseStreamClients(api.body(config, endpoints.about(config)))

    override suspend fun streamTunerSignal(config: ReceiverConfig, tunerNumber: Int?): SignalReading? {
        val key = "${config.id}@${config.host}:${config.httpPort}"
        if (frontendFiles[key] == false) return null
        if (tunerNumber != null) {
            val reading = readFrontend(config, tunerNumber) ?: return null
            return reading.takeIf { it.hasValues }
        }
        val locked = mutableListOf<SignalReading>()
        for (index in 0 until MAX_FRONTENDS) {
            val snr = readProc(config, index, "snr")
            if (snr is ProcFile.Unavailable) return null
            if (snr is ProcFile.Missing) return locked.singleOrNull()
            val reading = readingOf(config, index, (snr as ProcFile.Text).body) ?: continue
            val lock = readProc(config, index, "lock")
            val lockText = (lock as? ProcFile.Text)?.body
            if (FrontendSignal.locked(lockText, reading)) locked += reading
        }
        return locked.singleOrNull()
    }

    private suspend fun readFrontend(config: ReceiverConfig, tuner: Int): SignalReading? {
        val snr = readProc(config, tuner, "snr")
        if (snr !is ProcFile.Text) return null
        return readingOf(config, tuner, snr.body)
    }

    private suspend fun readingOf(config: ReceiverConfig, tuner: Int, snrBody: String): SignalReading? {
        val agc = (readProc(config, tuner, "signal") as? ProcFile.Text)?.body
        val db = (readProc(config, tuner, "snr_db") as? ProcFile.Text)?.body
        return FrontendSignal.parse(snrBody, agc, db, tuner)
    }

    private suspend fun readProc(config: ReceiverConfig, tuner: Int, name: String): ProcFile {
        val key = "${config.id}@${config.host}:${config.httpPort}"
        if (frontendFiles[key] == false) return ProcFile.Unavailable
        val cacheKey = "$key/$tuner/$name"
        if (cacheKey in missingProc) return ProcFile.Missing
        var missing = false
        for (url in endpoints.frontendFiles(config, tuner, name)) {
            try {
                val body = api.body(config, url)
                frontendFiles.putIfAbsent(key, true)
                return ProcFile.Text(body)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: OpenWebifException) {
                when (error.statusCode) {
                    404 -> missing = true
                    401, 403 -> {
                        frontendFiles[key] = false
                        EnigmaLog.osdInfo("frontend files forbidden")
                        return ProcFile.Unavailable
                    }
                    else -> return ProcFile.Missing
                }
            } catch (error: Exception) {
                EnigmaLog.osdInfo("frontend $tuner/$name failed: ${error.javaClass.simpleName}")
                return ProcFile.Missing
            }
        }
        if (missing) missingProc.add(cacheKey)
        return if (missing) ProcFile.Missing else ProcFile.Unavailable
    }

    override suspend fun enigmaSettings(config: ReceiverConfig): String? {
        val key = "${config.id}@${config.host}:${config.httpPort}"
        if (frontendFiles[key] == false) return null
        for (url in endpoints.fileDownload(config, ENIGMA_SETTINGS)) {
            try {
                val body = api.body(config, url)
                frontendFiles.putIfAbsent(key, true)
                return body
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: OpenWebifException) {
                if (error.statusCode == 403) {
                    frontendFiles[key] = false
                    EnigmaLog.osdInfo("frontend files forbidden")
                    return null
                }
            } catch (error: Exception) {
                EnigmaLog.osdInfo("enigma settings failed: ${error.javaClass.simpleName}")
                return null
            }
        }
        return null
    }

    private sealed interface ProcFile {
        data class Text(val body: String) : ProcFile
        data object Missing : ProcFile
        data object Unavailable : ProcFile
    }

    private companion object {
        const val MAX_FRONTENDS = 8
        const val ENIGMA_SETTINGS = "/etc/enigma2/settings"
    }
}
