package com.enigmacast.domain.xtream

/**
 * Where an IPTV or Torrent info page (or its player) opened from a library card returns to.
 * The origin is "home/<card key>" for a Home card and "<row>/<card key>" for a My Items card.
 */
object LibraryReturn {
    const val HOME_PREFIX = "home/"

    fun isHome(origin: String?): Boolean = origin?.startsWith(HOME_PREFIX) == true

    fun isMyItems(origin: String?): Boolean = origin != null && !isHome(origin)

    /**
     * Whether a page's own entry focus (its first card) must stand back because a return to a
     * card on that page is pending: otherwise both ask for focus and the entry can win.
     */
    fun entryFocusYields(returnKey: String?, homePage: Boolean): Boolean =
        if (homePage) isHome(returnKey) else isMyItems(returnKey)

    /** Back on an info page that a library card opened leaves the IPTV screen for that card. */
    fun infoBackLeaves(launchOrigin: String?): Boolean = launchOrigin != null

    /**
     * Row and card of [key] in rows of (row id, card keys), the key being "<row id>/<card key>".
     * Null when the row or the card is not (yet) there.
     */
    fun locate(key: String, rows: List<Pair<String, List<String>>>): Pair<Int, Int>? {
        val row = rows.indexOfFirst { (id, _) -> key.startsWith("$id/") }
        if (row < 0) return null
        val (id, cards) = rows[row]
        val card = cards.indexOfFirst { "$id/$it" == key }
        return if (card < 0) null else row to card
    }
}
