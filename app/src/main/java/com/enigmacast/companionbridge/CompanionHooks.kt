package com.enigmacast.companionbridge

/** Hooks die MainActivity zet; de brug kent de UI-laag verder niet. */
class CompanionHooks {
    @Volatile
    var openHome: () -> Unit = {}

    @Volatile
    var openPlayer: () -> Unit = {}

    @Volatile
    var openSettings: () -> Unit = {}

    @Volatile
    var screenName: () -> String = { "dashboard" }
}
