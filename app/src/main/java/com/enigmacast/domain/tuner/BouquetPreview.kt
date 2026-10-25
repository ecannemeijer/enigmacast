package com.enigmacast.domain.tuner

/**
 * Another bouquet opened in the channel list is only shown: CH+/CH−, digits and the remembered
 * last bouquet stay with the playing channel's list until a channel in the shown one is chosen.
 * [L] is the zap list snapshot (name, reference, channels).
 */
class BouquetPreview<L> {
    private var saved: L? = null

    val active: Boolean get() = saved != null

    /** The zap list before the first bouquet that is only shown; later ones keep that first one. */
    fun begin(current: L) {
        if (saved == null) saved = current
    }

    /** The shown list became the zap list (no going back). */
    fun commit() {
        saved = null
    }

    /** The list closed without a choice: the saved zap list, once. */
    fun restore(): L? = saved.also { saved = null }

    /**
     * A channel starts. A list handed over with it, or a channel from the shown list, commits; any
     * other channel (recent, previous, number, search) brings the saved list back. Returns the list
     * to put back, or null.
     */
    fun onStart(listGiven: Boolean, shownHolds: Boolean): L? =
        if (listGiven || shownHolds) { commit(); null } else restore()
}
