package com.enigmacast.domain.setup

/** The first-run wizard, in order. [optional] steps have a Skip button. */
enum class SetupStep(val optional: Boolean = false) {
    WELCOME,
    RECEIVER,
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
        fun showOnStart(receiverCount: Int): Boolean = receiverCount == 0
    }
}
