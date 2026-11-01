package com.enigmacast.domain.tuner

import com.enigmacast.domain.remote.EnigmaKey
import com.enigmacast.domain.remote.EnigmaKeyMapper

enum class OwnKeyAction { NEXT, PREVIOUS, OK, BACK, DETAILS, LIST, BANNER, DIGIT, CONSUME, PASS }

/**
 * Remote keys in own-tuner mode stay in the app: they zap this device's stream and drive
 * the app's own channel banner and list. Nothing is sent to the receiver.
 */
object OwnTunerKeys {
    private const val NUMPAD_0 = 144
    private const val NUMPAD_9 = 153
    private const val PAGE_UP = 92
    private const val PAGE_DOWN = 93

    fun action(keyCode: Int): OwnKeyAction {
        if (keyCode in NUMPAD_0..NUMPAD_9) return OwnKeyAction.DIGIT
        if (keyCode == PAGE_UP) return OwnKeyAction.NEXT
        if (keyCode == PAGE_DOWN) return OwnKeyAction.PREVIOUS
        return when (EnigmaKeyMapper.fromAndroidKeyCode(keyCode)) {
            null -> OwnKeyAction.PASS
            EnigmaKey.UP, EnigmaKey.CHANNEL_UP, EnigmaKey.NEXT -> OwnKeyAction.NEXT
            EnigmaKey.DOWN, EnigmaKey.CHANNEL_DOWN, EnigmaKey.PREVIOUS -> OwnKeyAction.PREVIOUS
            EnigmaKey.OK -> OwnKeyAction.OK
            EnigmaKey.EXIT -> OwnKeyAction.BACK
            EnigmaKey.INFO, EnigmaKey.EPG -> OwnKeyAction.DETAILS
            EnigmaKey.MENU, EnigmaKey.TV, EnigmaKey.LEFT -> OwnKeyAction.LIST
            EnigmaKey.RIGHT -> OwnKeyAction.BANNER
            EnigmaKey.DIGIT_0, EnigmaKey.DIGIT_1, EnigmaKey.DIGIT_2, EnigmaKey.DIGIT_3, EnigmaKey.DIGIT_4,
            EnigmaKey.DIGIT_5, EnigmaKey.DIGIT_6, EnigmaKey.DIGIT_7, EnigmaKey.DIGIT_8, EnigmaKey.DIGIT_9,
            -> OwnKeyAction.DIGIT
            else -> OwnKeyAction.CONSUME
        }
    }

    fun digit(keyCode: Int): Int {
        if (keyCode in NUMPAD_0..NUMPAD_9) return keyCode - NUMPAD_0
        val key = EnigmaKeyMapper.fromAndroidKeyCode(keyCode) ?: return -1
        return key.name.removePrefix("DIGIT_").toIntOrNull() ?: -1
    }
}

/** What OK does on live TV, by what the banner shows. */
object BannerOk {
    enum class Action { RETRY, OPTIONS_BANNER, PAUSE_OR_LIST, SHOW_BANNER }

    /**
     * A banner a zap brought up turns into the info banner (Down then opens the options) instead
     * of pausing; OK on that banner (or on radio) pauses as before; no banner: the banner.
     */
    fun decide(tunerTrouble: Boolean, radio: Boolean, banner: Boolean, fromZap: Boolean): Action = when {
        tunerTrouble -> Action.RETRY
        !radio && banner && fromZap -> Action.OPTIONS_BANNER
        radio || banner -> Action.PAUSE_OR_LIST
        else -> Action.SHOW_BANNER
    }
}
