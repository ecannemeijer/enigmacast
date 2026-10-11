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

    /**
     * The next box to try when [originId]'s tuners are full: the rest of the list after that
     * box, then the ones before it. Boxes in [tried] are skipped. Null when none remain.
     */
    fun nextAfter(receivers: List<ReceiverConfig>, originId: String?, tried: Set<String>): ReceiverConfig? {
        if (receivers.size < 2) return null
        val start = receivers.indexOfFirst { it.id == originId }.let { if (it < 0) 0 else it }
        return (receivers.drop(start + 1) + receivers.take(start)).firstOrNull { it.id !in tried }
    }
}
