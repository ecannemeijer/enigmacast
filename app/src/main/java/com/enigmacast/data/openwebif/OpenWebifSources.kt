package com.enigmacast.data.openwebif

import com.enigmacast.domain.model.BouquetChannelList
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
import com.enigmacast.domain.source.OsdSource
import com.enigmacast.domain.source.ReceiverInfoSource
import com.enigmacast.domain.source.RemoteControlSource
import com.enigmacast.domain.source.ServiceStateSource
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
    override suspend fun volume(config: ReceiverConfig): VolumeStatus = api.volume(config)
    override suspend fun bouquets(config: ReceiverConfig): List<BouquetEntry> = api.bouquets(config)
    override suspend fun services(config: ReceiverConfig, bouquetReference: String): List<BouquetEntry> {
        return api.services(config, bouquetReference)
    }
}
