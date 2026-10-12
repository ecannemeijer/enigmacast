package com.enigmacast.di

import com.enigmacast.data.network.TunnelControl
import com.enigmacast.data.network.WireGuardTunnel
import com.enigmacast.data.openwebif.OpenWebifEpgSource
import com.enigmacast.data.openwebif.OpenWebifOsdInfoSource
import com.enigmacast.data.openwebif.OpenWebifOsdSource
import com.enigmacast.data.openwebif.OpenWebifReceiverInfoSource
import com.enigmacast.data.openwebif.OpenWebifRemoteControlSource
import com.enigmacast.data.openwebif.OpenWebifServiceStateSource
import com.enigmacast.data.openwebif.OpenWebifTunerStatusSource
import com.enigmacast.data.security.KeystoreSecretStore
import com.enigmacast.data.security.SecretStore
import com.enigmacast.domain.source.EpgSource
import com.enigmacast.domain.source.OsdInfoSource
import com.enigmacast.domain.source.OsdSource
import com.enigmacast.domain.source.ReceiverInfoSource
import com.enigmacast.domain.source.RemoteControlSource
import com.enigmacast.domain.source.ServiceStateSource
import com.enigmacast.domain.source.TunerStatusSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SourceModule {
    @Binds
    @Singleton
    abstract fun osdSource(impl: OpenWebifOsdSource): OsdSource

    @Binds
    @Singleton
    abstract fun remoteSource(impl: OpenWebifRemoteControlSource): RemoteControlSource

    @Binds
    @Singleton
    abstract fun serviceSource(impl: com.enigmacast.data.openwebif.CachedServiceStateSource): ServiceStateSource

    @Binds
    @Singleton
    abstract fun guideCache(impl: com.enigmacast.sync.RoomGuideCache): com.enigmacast.domain.sync.GuideCacheReader

    @Binds
    @Singleton
    abstract fun infoSource(impl: OpenWebifReceiverInfoSource): ReceiverInfoSource

    @Binds
    @Singleton
    abstract fun tunerSource(impl: OpenWebifTunerStatusSource): TunerStatusSource

    @Binds
    @Singleton
    abstract fun epgSource(impl: OpenWebifEpgSource): EpgSource

    @Binds
    @Singleton
    abstract fun osdInfoSource(impl: OpenWebifOsdInfoSource): OsdInfoSource

    @Binds
    @Singleton
    abstract fun secretStore(impl: KeystoreSecretStore): SecretStore

    @Binds
    @Singleton
    abstract fun tunnelControl(impl: WireGuardTunnel): TunnelControl
}
