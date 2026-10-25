package com.enigmacast.domain.xtream

/**
 * The live channel list that channel up/down walks: the category of the channel that plays, not
 * the one shown in the panel. Browsing another category is only a preview; it becomes the zap
 * list when a channel in it is chosen. Channels are matched by id and provider (several providers
 * may use the same stream id).
 */
object IptvZapList {
    enum class Source {
        /** The shown list holds the new channel: it becomes the zap list. */
        SHOWN,
        /** The zap list already holds it (Right/recent/number in the same category). */
        KEPT,
        /** Neither does (started from outside the panel): its own category is fetched. */
        LOAD,
    }

    fun same(a: XtreamItem, b: XtreamItem): Boolean = a.id == b.id && a.providerId == b.providerId

    fun holds(list: List<XtreamItem>, item: XtreamItem): Boolean =
        list.any { it.section == XtreamSection.LIVE && same(it, item) }

    fun sameCategory(a: XtreamCategory?, b: XtreamCategory?): Boolean =
        a != null && b != null && a.id == b.id && a.providerId == b.providerId

    fun source(shownLive: Boolean, shown: List<XtreamItem>, zap: List<XtreamItem>, item: XtreamItem): Source = when {
        shownLive && holds(shown, item) -> Source.SHOWN
        holds(zap, item) -> Source.KEPT
        else -> Source.LOAD
    }

    /** The channel [step] places from [current] in [list] (wrapping); null when it is not in it. */
    fun step(list: List<XtreamItem>, current: XtreamItem, step: Int): XtreamItem? {
        val live = list.filter { it.section == XtreamSection.LIVE }
        val index = live.indexOfFirst { same(it, current) }.takeIf { it >= 0 } ?: return null
        return live.getOrNull((index + step).mod(live.size))
    }

    /**
     * The panel closed: the shown category goes back to the playing channel's one, when another
     * one was only browsed and the zap list holds the channel that plays.
     */
    fun needsRestore(
        shownCategory: XtreamCategory?,
        zapCategory: XtreamCategory?,
        zap: List<XtreamItem>,
        playing: XtreamItem?,
    ): Boolean = playing != null && playing.section == XtreamSection.LIVE && zapCategory != null &&
        holds(zap, playing) && !sameCategory(shownCategory, zapCategory)
}
