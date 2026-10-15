package com.enigmacast.domain.setup

/** The first-run wizard, in order. [optional] steps have a Skip button. */
enum class Start { DASHBOARD, SETUP, CONNECTION }

enum class SetupStep(val optional: Boolean = false) {
    WELCOME,

    /** Create an EnigmaCast account or log in (starts the Premium trial). */
    ACCOUNT,
    RECEIVER,
    /** Search the home network for the sync server, then pair or stay standalone. */
    SYNC_LINK,
    /** A server IPTV login, only when one is waiting. The channel list loads after the bouquets. */
    IPTV_OFFER,
    PROFILE(optional = true),
    VIEWING,
    BOUQUETS(optional = true),
    SYNC,
    SOCCER(optional = true),
    DONE,
    ;

    companion object {
        fun next(step: SetupStep): SetupStep = entries.getOrElse(step.ordinal + 1) { DONE }
        fun previous(step: SetupStep): SetupStep? = entries.getOrNull(step.ordinal - 1)

        /** Only a fresh install sees the wizard by itself: no receiver set up yet. */
        fun showOnStart(receiverCount: Int, completed: Boolean = false): Boolean = receiverCount == 0 && !completed

        /** Where the app opens: dashboard with a receiver; otherwise the wizard, or the connection screen after it was done once. */
        fun startScreen(receiverCount: Int, completed: Boolean): Start = when {
            receiverCount > 0 -> Start.DASHBOARD
            completed -> Start.CONNECTION
            else -> Start.SETUP
        }
    }
}
