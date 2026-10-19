package com.enigmacast.domain.xtream

/** What OK on a My Items IPTV/Torrent card does; a long press (or Menu) always shows the options. */
object LibraryCardAction {
    enum class Ok { INFO, OPTIONS }

    /**
     * Films, series, episodes and Torrent titles open their info page (Play and the favourite
     * toggle are there). A live channel has no info page: OK keeps showing the options.
     */
    fun onOk(torrent: Boolean, section: XtreamSection): Ok = when {
        torrent -> Ok.INFO
        section == XtreamSection.LIVE -> Ok.OPTIONS
        else -> Ok.INFO
    }
}
