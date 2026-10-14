package com.enigmacast.domain.model

import com.enigmacast.domain.player.RecentChannels

/**
 * Last channel and recent channels are stored per receiver.
 * Older settings kept one [AppSettings.lastChannel] and one [AppSettings.recentChannels]
 * for the only box; [migrate] hangs those on the active receiver.
 */
object ChannelMemory {
    /** Same channel lists on another box: its last and recent channels start from [fromId]'s, unless it has its own. */
    fun carryOver(settings: AppSettings, fromId: String, toId: String): AppSettings {
        if (fromId.isBlank() || toId.isBlank() || fromId == toId) return settings
        val last = settings.lastChannels[fromId]
        val recent = settings.recentByReceiver[fromId]
        return settings.copy(
            lastChannels = if (last != null && toId !in settings.lastChannels) settings.lastChannels + (toId to last) else settings.lastChannels,
            recentByReceiver = if (recent != null && toId !in settings.recentByReceiver) settings.recentByReceiver + (toId to recent) else settings.recentByReceiver,
        )
    }

    fun needsMigration(settings: AppSettings): Boolean {
        if (settings.activeReceiver()?.id.isNullOrBlank()) return false
        return settings.lastChannel.isRemembered() || settings.recentChannels.isNotEmpty()
    }

    /** Moves the legacy single channel onto the active receiver and clears the legacy fields. */
    fun migrate(settings: AppSettings): AppSettings {
        if (!needsMigration(settings)) return settings
        val id = settings.activeReceiver()?.id?.takeIf { it.isNotBlank() } ?: return settings
        val lastChannels = if (id !in settings.lastChannels && settings.lastChannel.isRemembered()) {
            settings.lastChannels + (id to settings.lastChannel)
        } else {
            settings.lastChannels
        }
        val recent = if (id !in settings.recentByReceiver && settings.recentChannels.isNotEmpty()) {
            settings.recentByReceiver + (id to settings.recentChannels)
        } else {
            settings.recentByReceiver
        }
        return settings.copy(
            lastChannels = lastChannels,
            recentByReceiver = recent,
            lastChannel = LastChannel(),
            recentChannels = emptyList(),
        )
    }

    fun lastFor(settings: AppSettings, receiverId: String): LastChannel {
        settings.lastChannels[receiverId]?.let { return it }
        if (receiverId.isNotBlank() && receiverId == settings.activeReceiver()?.id && settings.lastChannel.isRemembered()) {
            return settings.lastChannel
        }
        return LastChannel()
    }

    fun recentFor(settings: AppSettings, receiverId: String): List<LastChannel> {
        settings.recentByReceiver[receiverId]?.let { return it }
        if (receiverId.isNotBlank() && receiverId == settings.activeReceiver()?.id) return settings.recentChannels
        return emptyList()
    }

    fun remember(settings: AppSettings, receiverId: String, last: LastChannel): AppSettings {
        if (receiverId.isBlank()) return settings
        val recent = RecentChannels.push(recentFor(settings, receiverId), last)
        return settings.copy(
            lastChannels = settings.lastChannels + (receiverId to last),
            recentByReceiver = settings.recentByReceiver + (receiverId to recent),
            lastLiveSource = LastLiveSource.ENIGMA2,
        )
    }

    /** An IPTV live channel started: remember it, and start this one next time the app opens. */
    fun rememberIptv(settings: AppSettings, channel: LastIptvChannel): AppSettings {
        if (channel.source.isBlank() || channel.streamId.isBlank()) return settings
        return settings.copy(
            lastLiveSource = LastLiveSource.IPTV,
            lastIptvChannels = settings.lastIptvChannels + (channel.source to channel),
        )
    }

    fun rememberBouquet(settings: AppSettings, receiverId: String, bouquetReference: String): AppSettings {
        if (receiverId.isBlank()) return settings
        val last = lastFor(settings, receiverId).copy(bouquetReference = bouquetReference)
        return settings.copy(lastChannels = settings.lastChannels + (receiverId to last))
    }
}

private fun LastChannel.isRemembered(): Boolean =
    reference.isNotBlank() || name.isNotBlank() || bouquetReference.isNotBlank()
