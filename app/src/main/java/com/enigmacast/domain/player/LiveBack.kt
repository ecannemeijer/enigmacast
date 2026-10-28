package com.enigmacast.domain.player

/** What Back does on the full-screen live picture (satellite and IPTV). */
enum class LiveBackStep { HIDE_OSD, STOP_INSET, GUIDE }

/**
 * Back on live TV closes one layer at a time. Lists, the options bar, search and dialogs are
 * closed by the player before this is asked; then the info bar (OSD) at the bottom closes and
 * the channel stays full screen, then a picture-in-picture inset stops, and only Back on the
 * bare picture opens the TV guide.
 */
object LiveBack {
    fun onPicture(osdVisible: Boolean, insetPlaying: Boolean): LiveBackStep = when {
        osdVisible -> LiveBackStep.HIDE_OSD
        insetPlaying -> LiveBackStep.STOP_INSET
        else -> LiveBackStep.GUIDE
    }
}
