package com.enigmacast.domain.ui

/**
 * Left on the first item of a horizontal option row goes to the last one, Right on the last
 * to the first (the player OSD rows). Like the vertical lists (wrapListEnds), a held key does
 * not wrap: auto-repeat would spin round the row, so it stops at the end; a new press wraps.
 */
object RowWrap {
    enum class Move {
        /** Not at that end (or a row of one): the normal focus move. */
        NONE,
        TO_FIRST,
        TO_LAST,

        /** At the end with a held key: stay put. */
        HOLD,
    }

    /** [forward] is Right, else Left; [repeat] marks an auto-repeat of a held key. */
    fun at(index: Int, lastIndex: Int, forward: Boolean, repeat: Boolean): Move {
        if (lastIndex <= 0) return Move.NONE
        val atEnd = if (forward) index >= lastIndex else index <= 0
        return when {
            !atEnd -> Move.NONE
            repeat -> Move.HOLD
            forward -> Move.TO_FIRST
            else -> Move.TO_LAST
        }
    }

    /** The next selected index of a row of [size] that keeps its own selection. */
    fun step(index: Int, size: Int, forward: Boolean, repeat: Boolean): Int {
        if (size <= 0) return 0
        val last = size - 1
        val current = index.coerceIn(0, last)
        return when (at(current, last, forward, repeat)) {
            Move.TO_FIRST -> 0
            Move.TO_LAST -> last
            Move.HOLD -> current
            Move.NONE -> (current + if (forward) 1 else -1).coerceIn(0, last)
        }
    }
}
