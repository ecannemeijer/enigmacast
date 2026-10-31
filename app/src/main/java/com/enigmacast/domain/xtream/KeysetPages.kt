package com.enigmacast.domain.xtream

/**
 * A category read in two steps: a small first chunk the page can show at once, then the rest.
 * Pages continue after the last row read (sort, id) instead of LIMIT/OFFSET, so every page is an
 * index seek; OFFSET re-walks all rows before it and slowed down page by page in a 9000-film group.
 */
object KeysetPages {
    /** Films and series shown before the rest of a category is read. */
    const val FIRST_CHUNK = 200

    /** Rows per later page (each one bounded so a page never fills a CursorWindow). */
    const val PAGE = 1000

    data class After(val sort: Int, val id: String)

    /**
     * Size of the first chunk: [FIRST_CHUNK], or enough to hold the remembered grid poster
     * ([anchor]) plus a screen around it, so Back to the grid lands on it without waiting.
     */
    fun firstChunk(anchor: Int): Int = maxOf(FIRST_CHUNK, if (anchor > 0) anchor + FIRST_CHUNK else 0)

    /**
     * Reads everything with [fetch] (rows after the given position, at most the given count) and
     * hands it over in at most two parts: the first chunk, then the remainder in one piece
     * (`last = true`). A category that fits in the first chunk is one call with `last = true`.
     */
    suspend fun <R> read(
        first: Int,
        page: Int = PAGE,
        fetch: suspend (after: After?, limit: Int) -> List<R>,
        position: (R) -> After,
        onChunk: suspend (rows: List<R>, last: Boolean) -> Unit,
    ) {
        val head = fetch(null, first)
        if (head.size < first) {
            onChunk(head, true)
            return
        }
        onChunk(head, false)
        val rest = ArrayList<R>()
        var after = position(head.last())
        while (true) {
            val rows = fetch(after, page)
            rest.addAll(rows)
            if (rows.size < page) break
            after = position(rows.last())
        }
        onChunk(rest, true)
    }
}
