package com.enigmacast.domain.source

import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.search.EpgSearchEvent
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
import com.enigmacast.domain.osdinfo.ReceiverCurrent
import com.enigmacast.domain.osdinfo.SignalReading
import com.enigmacast.domain.osdinfo.StreamClientInfo
import com.enigmacast.domain.osd.GrabMode
import com.enigmacast.domain.osd.GrabbedImage
import com.enigmacast.domain.remote.EnigmaKey
import com.enigmacast.domain.remote.KeyAction
import com.enigmacast.domain.tuner.NowEvent
import com.enigmacast.domain.tuner.TunerSnapshot

interface OsdSource {
    suspend fun grab(config: ReceiverConfig, format: GrabFormat, mode: GrabMode): GrabbedImage
}

interface RemoteControlSource {
    suspend fun sendKey(config: ReceiverConfig, key: EnigmaKey, action: KeyAction)
}

interface ServiceStateSource {
    suspend fun currentService(config: ReceiverConfig): EnigmaService
    suspend fun channelGuide(config: ReceiverConfig): ChannelGuide
    suspend fun channelGuide(config: ReceiverConfig, serviceReference: String, channelName: String): ChannelGuide
    suspend fun zap(config: ReceiverConfig, serviceReference: String)
    suspend fun currentBouquetChannels(config: ReceiverConfig): BouquetChannelList
    suspend fun televisionBouquets(config: ReceiverConfig): List<BouquetEntry>
    suspend fun radioBouquets(config: ReceiverConfig): List<BouquetEntry>
    suspend fun channelsInBouquet(config: ReceiverConfig, bouquetReference: String): List<BouquetEntry>
}

interface ReceiverInfoSource {
    suspend fun capabilities(config: ReceiverConfig): ReceiverCapabilities
    suspend fun powerState(config: ReceiverConfig): PowerStatus
    suspend fun wake(config: ReceiverConfig)
    suspend fun setPowerState(config: ReceiverConfig, action: PowerAction)
    suspend fun details(config: ReceiverConfig): ReceiverDetails
    suspend fun sleepTimer(config: ReceiverConfig): SleepTimerStatus
    suspend fun setSleepTimer(
        config: ReceiverConfig,
        minutes: Int,
        action: SleepTimerAction,
        enabled: Boolean,
    ): SleepTimerStatus
    suspend fun volume(config: ReceiverConfig): VolumeStatus
    suspend fun bouquets(config: ReceiverConfig): List<BouquetEntry>
    suspend fun services(config: ReceiverConfig, bouquetReference: String): List<BouquetEntry>
}

interface TunerStatusSource {
    /** What the receiver's tuners are doing now: own picture, recordings and stream clients. */
    suspend fun snapshot(config: ReceiverConfig): TunerSnapshot

    /** Bouquet reference → Enigma2 number before its first channel, for channel numbers. */
    suspend fun bouquetStartPositions(config: ReceiverConfig): Map<String, Int>

    /** Raw picon bytes from the receiver, or null when it has none. */
    suspend fun picon(config: ReceiverConfig, path: String): ByteArray?

    /** What is on air now on every channel of a bouquet. */
    suspend fun nowEvents(config: ReceiverConfig, bouquetReference: String): List<NowEvent>
}

interface EpgSource {
    /** Every programme the receiver knows for one channel. */
    suspend fun serviceEvents(config: ReceiverConfig, serviceReference: String): List<GuideEvent>

    /** The programmes of all channels in a bouquet between [startSec] and [minutes] later. */
    suspend fun bouquetEvents(config: ReceiverConfig, bouquetReference: String, startSec: Long, minutes: Int): List<GuideEvent>

    /** Asks the receiver to record one programme. */
    suspend fun record(config: ReceiverConfig, serviceReference: String, eventId: Int): TimerResult

    /** Programmes whose title matches [query], across every bouquet (`/api/epgsearch`). */
    suspend fun search(config: ReceiverConfig, query: String): List<EpgSearchEvent>
}

/** Receiver-side data for the OSD info strip (OpenWebif). */
interface OsdInfoSource {
    /** Signal of the receiver's current frontend; null when the image has no signal endpoint. */
    suspend fun signal(config: ReceiverConfig): SignalReading?

    /** The receiver's own current service (`/api/getcurrent`). */
    suspend fun current(config: ReceiverConfig): ReceiverCurrent

    /** Stream clients from `/api/about`, with tuner and transponder on openATV. */
    suspend fun streamClients(config: ReceiverConfig): List<StreamClientInfo>

    /**
     * Signal of the tuner that serves this stream, from the receiver's frontend files.
     * [tunerNumber] null asks for the only locked frontend. Null when the files are missing
     * or more than one frontend is locked and the tuner is unknown.
     */
    suspend fun streamTunerSignal(config: ReceiverConfig, tunerNumber: Int?): SignalReading?

    /**
     * `/etc/enigma2/settings` via OpenWebif's file download.
     * Null when the image forbids file download or the file is missing.
     */
    suspend fun enigmaSettings(config: ReceiverConfig): String?

    /**
     * The receiver channel database (`lamedb`, or `lamedb5` when that is the only copy).
     * Null when file download is forbidden or both files are missing.
     */
    suspend fun lamedb(config: ReceiverConfig): String?
}
