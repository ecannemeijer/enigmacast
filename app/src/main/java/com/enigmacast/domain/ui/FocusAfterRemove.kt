package com.enigmacast.domain.ui

/**
 * Where the focus goes when an item is removed from a page of rows: the next item in the same
 * row (same place), the previous one when it was the last; an emptied row hands over to the
 * nearest item in the row below (same column, clamped), else the row above. Null: nothing left.
 */
object FocusAfterRemove {
    fun next(rows: List<List<String>>, removed: String): String? {
        val r = rows.indexOfFirst { removed in it }
        if (r < 0) return null
        val row = rows[r]
        val i = row.indexOf(removed)
        if (row.size > 1) return if (i < row.lastIndex) row[i + 1] else row[i - 1]
        for (other in (r + 1..rows.lastIndex) + (r - 1 downTo 0)) {
            val keys = rows[other]
            if (keys.isNotEmpty()) return keys[i.coerceAtMost(keys.lastIndex)]
        }
        return null
    }
}
