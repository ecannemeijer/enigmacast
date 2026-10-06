package com.enigmacast.domain.source

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
