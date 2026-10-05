package com.enigmacast.domain.source

import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ChannelGuide
import com.enigmacast.domain.model.EnigmaService
import com.enigmacast.domain.model.PowerStatus
import com.enigmacast.domain.model.ReceiverCapabilities
import com.enigmacast.domain.model.ReceiverConfig
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
}

interface ReceiverInfoSource {
    suspend fun capabilities(config: ReceiverConfig): ReceiverCapabilities
    suspend fun powerState(config: ReceiverConfig): PowerStatus
    suspend fun volume(config: ReceiverConfig): VolumeStatus
    suspend fun bouquets(config: ReceiverConfig): List<BouquetEntry>
    suspend fun services(config: ReceiverConfig, bouquetReference: String): List<BouquetEntry>
}
