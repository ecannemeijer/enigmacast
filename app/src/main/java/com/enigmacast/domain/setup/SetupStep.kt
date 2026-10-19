package com.enigmacast.domain.setup

enum class Start { DASHBOARD, SETUP, CONNECTION }

/**
 * The first-run wizard. The standard path is Language › Receiver › Viewing mode › Account › Done;
 * Sync server and IPTV only show up when the network offers them ([SetupFlow]). [extra] steps are
 * opened from Done and go back there. [optional] steps have a Skip button.
 */
enum class SetupStep(val optional: Boolean = false, val extra: Boolean = false) {
    WELCOME,
    RECEIVER,

    /** Only when a sync server answered on the receiver: pair, or Not now. */
    SYNC_LINK(optional = true),

    /** A server IPTV login, only when one is waiting. */
    IPTV_OFFER(optional = true),
    VIEWING,

    /** Create an EnigmaCast account or log in; skipping keeps the free plan. */
    ACCOUNT(optional = true),
    DONE,
    PROFILE(optional = true, extra = true),
    BOUQUETS(optional = true, extra = true),
    ;

    companion object {
        /** Only a fresh install sees the wizard by itself: no receiver set up yet. */
        fun showOnStart(receiverCount: Int, completed: Boolean = false): Boolean = receiverCount == 0 && !completed

        /** Where the app opens: dashboard with a receiver; otherwise the wizard, or the connection screen after it was done once. */
        fun startScreen(receiverCount: Int, completed: Boolean): Start = when {
            receiverCount > 0 -> Start.DASHBOARD
            completed -> Start.CONNECTION
            else -> Start.SETUP
        }

        /**
         * The update dialog waits until the first setup is behind the viewer: never over the
         * wizard, and not before a receiver or a finished setup exists.
         */
        fun allowsUpdatePrompt(receiverCount: Int, completed: Boolean, onSetupScreen: Boolean): Boolean =
            !onSetupScreen && (completed || receiverCount > 0)
    }
}

/** What the wizard knows right now; decides which conditional steps are part of the path. */
data class SetupConditions(
    /** A sync server is being looked for, or one was found on the receiver (not paired before the wizard). */
    val syncOffered: Boolean = false,
    /** The sync server has an IPTV login waiting for a yes or no. */
    val iptvOffered: Boolean = false,
    /** Paired with a sync server: the profile is chosen on "Who's watching?" after the wizard. */
    val paired: Boolean = false,
    val hasReceiver: Boolean = false,
)

object SetupFlow {
    /** The main path, in order. Extras are not part of it. */
    fun steps(conditions: SetupConditions): List<SetupStep> = SetupStep.entries.filter { step ->
        when (step) {
            SetupStep.SYNC_LINK -> conditions.syncOffered
            SetupStep.IPTV_OFFER -> conditions.iptvOffered
            else -> !step.extra
        }
    }

    /** Optional extras offered on Done. */
    fun extras(conditions: SetupConditions): List<SetupStep> = buildList {
        if (!conditions.paired) add(SetupStep.PROFILE)
        if (conditions.hasReceiver) add(SetupStep.BOUQUETS)
    }

    /** The step after [step]. An extra returns to Done; Done stays Done. */
    fun next(step: SetupStep, conditions: SetupConditions): SetupStep {
        if (step.extra || step == SetupStep.DONE) return SetupStep.DONE
        return steps(conditions).firstOrNull { it.ordinal > step.ordinal } ?: SetupStep.DONE
    }

    /** The step before [step], or null on the first one. An extra goes back to Done. */
    fun previous(step: SetupStep, conditions: SetupConditions): SetupStep? {
        if (step.extra) return SetupStep.DONE
        return steps(conditions).lastOrNull { it.ordinal < step.ordinal }
    }

    /** 1-based position and total on the main path; null for an extra. */
    fun position(step: SetupStep, conditions: SetupConditions): Pair<Int, Int>? {
        if (step.extra) return null
        val list = steps(conditions)
        val index = list.indexOf(step).takeIf { it >= 0 } ?: list.count { it.ordinal < step.ordinal }
        return (index + 1) to list.size
    }
}
