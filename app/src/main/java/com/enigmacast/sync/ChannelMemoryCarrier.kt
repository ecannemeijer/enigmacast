package com.enigmacast.sync

import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.model.ChannelMemory
import javax.inject.Inject
import javax.inject.Singleton

/** Copies the last/recent channels to a box with identical channel lists. */
@Singleton
class ChannelMemoryCarrier @Inject constructor(private val settings: SettingsRepository) {
    suspend fun carryOver(fromId: String, toId: String) = settings.update { ChannelMemory.carryOver(it, fromId, toId) }
}
