package com.enigmacast.domain.person

import com.enigmacast.domain.torrent.TmdbTitle
import com.enigmacast.domain.xtream.XtreamItem

/** The info page a "where to watch" choice was made from, so Back can reopen it. */
sealed interface TrailOrigin {
    /** An IPTV film or series info page; [launchOrigin] is where that page itself returns to (Home/Library card). */
    data class Iptv(val source: String, val item: XtreamItem, val launchOrigin: String?) : TrailOrigin

    /** A Torrent info page; [origin] is where that page itself returns to (Home/Library card). */
    data class Torrent(val title: TmdbTitle, val origin: String?) : TrailOrigin

    /** Whether [other] is the same info page (same title), whatever its return target. */
    fun samePage(other: TrailOrigin): Boolean = when {
        this is Iptv && other is Iptv -> item.section == other.item.section && item.id == other.item.id && source == other.source
        this is Torrent && other is Torrent -> title.key == other.title.key
        else -> false
    }
}

/**
 * One "where to watch" jump: from the info page [origin], over the actor [actorKey] and the
 * filmography title [creditKey], to the page [targetKey] that opened ("iptv:…" or "tmdb:…").
 */
data class TrailStep(
    val origin: TrailOrigin,
    val actorKey: String,
    val creditKey: String,
    val targetKey: String,
)

/**
 * The way back from a title opened on the actor page's "where to watch": Back on that title's
 * info page reopens the info page it came from, with the actor page and "where to watch" on top
 * (so Back walks: title → where to watch → actor → the original info page).
 */
class TitleTrail(private val maxDepth: Int = MAX_DEPTH) {
    private val steps = ArrayDeque<TrailStep>()

    /** The step being reopened: its info page opens the actor page and "where to watch" again. */
    var restore: TrailStep? = null
        private set

    val depth: Int get() = steps.size

    fun push(step: TrailStep) {
        // Choosing again from a reopened page replaces nothing: the trail just grows (bounded).
        steps.addLast(step)
        while (steps.size > maxDepth) steps.removeFirst()
    }

    /**
     * Back on the info page [closingKey]: the step that opened it, now marked for [restore];
     * null when that page was not opened from "where to watch" (normal Back).
     */
    fun back(closingKey: String): TrailStep? {
        val top = steps.lastOrNull() ?: return null
        if (top.targetKey != closingKey) return null
        steps.removeLast()
        restore = top
        return top
    }

    /** The info page [page] opened: the actor to reopen on it (once), or null. */
    fun takeRestore(page: TrailOrigin): TrailStep? {
        val step = restore ?: return null
        if (!step.origin.samePage(page)) return null
        restore = null
        return step
    }

    /** The actor page reopened [actorKey]: the filmography title whose "where to watch" opens again (once). */
    private var creditRestore: Pair<String, String>? = null

    fun markCredit(step: TrailStep) { creditRestore = step.actorKey to step.creditKey }

    fun takeCredit(actorKey: String): String? {
        val (actor, credit) = creditRestore ?: return null
        if (actor != actorKey) return null
        creditRestore = null
        return credit
    }

    /** Left by other means (Home, another screen): no way back is kept. */
    fun clear() {
        steps.clear()
        restore = null
        creditRestore = null
    }

    companion object {
        const val MAX_DEPTH = 8

        fun iptvKey(item: XtreamItem) = "iptv:${item.section}:${item.id}"
        fun tmdbKey(title: TmdbTitle) = "tmdb:${title.key}"
        fun actorKey(name: String, character: String) = "$name|$character"
    }
}
