package com.enigmacast.data.epg

import com.enigmacast.domain.epg.FallbackEpgLookup
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class FallbackEpgModule {
    @Binds
    @Singleton
    abstract fun bind(impl: FallbackEpg): FallbackEpgLookup
}
