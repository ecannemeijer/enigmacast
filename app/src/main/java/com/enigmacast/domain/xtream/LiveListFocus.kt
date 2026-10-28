package com.enigmacast.domain.xtream

/**
 * Which row of the live TV channel list takes the focus when the list is entered: on opening,
 * Left from the programme panel, Right from the categories, Back from the panel, or a newly
 * loaded category. Rows are named by their lazy-list key ([LazyKeys]), never by a remembered
 * position, so a list that was scrolled or laid out again still finds the same channel.
 *
 * Order: the channel last focused in this list (the one whose programmes were shown), the
 * playing channel, the first row fully on screen, the first row. Only an empty list has no
 * target (-1); the caller then stays in the categories. A list with rows is always entered.
 */
object LiveListFocus {
    fun target(keys: List<String>, remembered: String?, playingIndex: Int, visible: List<Int>): Int {
        if (keys.isEmpty()) return -1
        if (!remembered.isNullOrBlank()) {
            val at = keys.indexOf(remembered)
            if (at >= 0) return at
        }
        if (playingIndex in keys.indices) return playingIndex
        visible.firstOrNull { it in keys.indices }?.let { return it }
        return 0
    }

    /** The target row is not laid out on screen: the list scrolls to it before asking for focus. */
    fun needsScroll(target: Int, visible: List<Int>): Boolean = target >= 0 && target !in visible
}
