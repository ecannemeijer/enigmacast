package com.enigmacast.domain.remote

/**
 * Android KeyEvent values. Duplicated here so mapping tests run on the JVM
 * without the Android SDK. They match android.view.KeyEvent constants.
 */
object AndroidKeyCodes {
    const val BACK = 4
    const val DIGIT_0 = 7
    const val DIGIT_1 = 8
    const val DIGIT_2 = 9
    const val DIGIT_3 = 10
    const val DIGIT_4 = 11
    const val DIGIT_5 = 12
    const val DIGIT_6 = 13
    const val DIGIT_7 = 14
    const val DIGIT_8 = 15
    const val DIGIT_9 = 16
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val MEDIA_PLAY_PAUSE = 85
    const val MEDIA_STOP = 86
    const val MEDIA_NEXT = 87
    const val MEDIA_PREVIOUS = 88
    const val MEDIA_REWIND = 89
    const val MEDIA_FAST_FORWARD = 90
    const val MENU = 82
    const val TV_CONTENTS_MENU = 256
    const val TV_MEDIA_CONTEXT_MENU = 257
    const val MEDIA_PLAY = 126
    const val MEDIA_PAUSE = 127
    const val MEDIA_RECORD = 130
    const val ENTER = 66
    const val INFO = 165
    const val CHANNEL_UP = 166
    const val CHANNEL_DOWN = 167
    const val GUIDE = 172
    const val TV = 170
    const val PROG_RED = 183
    const val PROG_GREEN = 184
    const val PROG_YELLOW = 185
    const val PROG_BLUE = 186
    const val TV_TELETEXT = 233
    const val TV_RADIO = 232
    const val HELP = 259
    const val MEDIA_AUDIO_TRACK = 222
}

object EnigmaKeyMapper {
    fun fromAndroidKeyCode(keyCode: Int): EnigmaKey? {
        return when (keyCode) {
            AndroidKeyCodes.DPAD_UP -> EnigmaKey.UP
            AndroidKeyCodes.DPAD_DOWN -> EnigmaKey.DOWN
            AndroidKeyCodes.DPAD_LEFT -> EnigmaKey.LEFT
            AndroidKeyCodes.DPAD_RIGHT -> EnigmaKey.RIGHT
            AndroidKeyCodes.DPAD_CENTER, AndroidKeyCodes.ENTER -> EnigmaKey.OK
            AndroidKeyCodes.BACK -> EnigmaKey.EXIT
            AndroidKeyCodes.MENU,
            AndroidKeyCodes.TV_CONTENTS_MENU,
            AndroidKeyCodes.TV_MEDIA_CONTEXT_MENU,
            -> EnigmaKey.MENU
            AndroidKeyCodes.INFO -> EnigmaKey.INFO
            AndroidKeyCodes.GUIDE -> EnigmaKey.EPG
            AndroidKeyCodes.CHANNEL_UP -> EnigmaKey.CHANNEL_UP
            AndroidKeyCodes.CHANNEL_DOWN -> EnigmaKey.CHANNEL_DOWN
            AndroidKeyCodes.PROG_RED -> EnigmaKey.RED
            AndroidKeyCodes.PROG_GREEN -> EnigmaKey.GREEN
            AndroidKeyCodes.PROG_YELLOW -> EnigmaKey.YELLOW
            AndroidKeyCodes.PROG_BLUE -> EnigmaKey.BLUE
            AndroidKeyCodes.MEDIA_PLAY -> EnigmaKey.PLAY
            AndroidKeyCodes.MEDIA_PAUSE -> EnigmaKey.PAUSE
            AndroidKeyCodes.MEDIA_PLAY_PAUSE -> EnigmaKey.PLAY_PAUSE
            AndroidKeyCodes.MEDIA_STOP -> EnigmaKey.STOP
            AndroidKeyCodes.MEDIA_RECORD -> EnigmaKey.RECORD
            AndroidKeyCodes.MEDIA_NEXT -> EnigmaKey.NEXT
            AndroidKeyCodes.MEDIA_PREVIOUS -> EnigmaKey.PREVIOUS
            AndroidKeyCodes.MEDIA_FAST_FORWARD -> EnigmaKey.FAST_FORWARD
            AndroidKeyCodes.MEDIA_REWIND -> EnigmaKey.REWIND
            AndroidKeyCodes.TV_TELETEXT -> EnigmaKey.TEXT
            AndroidKeyCodes.HELP -> EnigmaKey.HELP
            AndroidKeyCodes.MEDIA_AUDIO_TRACK -> EnigmaKey.AUDIO
            AndroidKeyCodes.TV -> EnigmaKey.TV
            AndroidKeyCodes.TV_RADIO -> EnigmaKey.RADIO
            AndroidKeyCodes.DIGIT_0 -> EnigmaKey.DIGIT_0
            AndroidKeyCodes.DIGIT_1 -> EnigmaKey.DIGIT_1
            AndroidKeyCodes.DIGIT_2 -> EnigmaKey.DIGIT_2
            AndroidKeyCodes.DIGIT_3 -> EnigmaKey.DIGIT_3
            AndroidKeyCodes.DIGIT_4 -> EnigmaKey.DIGIT_4
            AndroidKeyCodes.DIGIT_5 -> EnigmaKey.DIGIT_5
            AndroidKeyCodes.DIGIT_6 -> EnigmaKey.DIGIT_6
            AndroidKeyCodes.DIGIT_7 -> EnigmaKey.DIGIT_7
            AndroidKeyCodes.DIGIT_8 -> EnigmaKey.DIGIT_8
            AndroidKeyCodes.DIGIT_9 -> EnigmaKey.DIGIT_9
            else -> null
        }
    }
}
