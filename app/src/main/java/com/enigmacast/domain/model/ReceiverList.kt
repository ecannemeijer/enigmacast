package com.enigmacast.domain.model

/** Choosing and removing boxes in Settings. The chosen box stays chosen until the user picks another. */
object ReceiverList {
    fun select(settings: AppSettings, id: String): AppSettings {
        if (settings.receivers.none { it.id == id }) return settings
        if (settings.activeReceiverId == id) return settings
        return settings.copy(activeReceiverId = id)
    }

    /**
     * Drops the box and its channel memory.
     * When it was the one in use, the next box in the list becomes active, or none when it was the last.
     */
    fun remove(settings: AppSettings, id: String): AppSettings {
        val index = settings.receivers.indexOfFirst { it.id == id }
        if (index < 0) return settings
        val remaining = settings.receivers.filterNot { it.id == id }
        val wasActive = settings.activeReceiver()?.id == id
        val activeId = if (!wasActive) {
            settings.activeReceiverId
        } else {
            (remaining.getOrNull(index) ?: remaining.lastOrNull())?.id
        }
        return settings.copy(
            receivers = remaining,
            activeReceiverId = activeId,
            lastChannels = settings.lastChannels - id,
            recentByReceiver = settings.recentByReceiver - id,
        )
    }
}
