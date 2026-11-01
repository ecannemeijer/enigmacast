package com.enigmacast.domain.search

import com.enigmacast.domain.xtream.LibraryReturn

/** Where the focus goes on the Search page: back on a chosen result, or the first result after a search. */
object SearchFocus {
    /**
     * List item and card of the result a Search origin points at ("<row id>/<card key>"), the IPTV
     * rows coming after [leadingItems] other list items (the actors row). Null when it is gone.
     */
    fun returnSpot(key: String, rows: List<Pair<String, List<String>>>, leadingItems: Int): Pair<Int, Int>? =
        LibraryReturn.locate(key, rows)?.let { (row, card) -> row + leadingItems to card }

    /** Which block of the list holds the first result: the actors row, the IPTV rows, or the programme hits. */
    enum class First { NONE, ACTORS, IPTV, HITS }

    fun first(people: Int, iptvRows: Int, hitSections: Int): First = when {
        people > 0 -> First.ACTORS
        iptvRows > 0 -> First.IPTV
        hitSections > 0 -> First.HITS
        else -> First.NONE
    }

    /**
     * After OK / Done in the query field: the first result takes the focus once the search is done
     * and has something (a pending wish without results stays on the field).
     */
    fun focusFirstNow(pending: Boolean, searching: Boolean, hasResults: Boolean): Boolean =
        pending && hasResults && !searching
}
