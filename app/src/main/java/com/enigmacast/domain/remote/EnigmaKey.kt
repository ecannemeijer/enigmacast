package com.enigmacast.domain.remote

/**
 * Linux input event codes passed through by OpenWebif `remoteControl()` to
 * `eActionMap.keyPressed`. Verified against oe-alliance OpenWebif `control.py`:
 * a normal command is key-down (flag 0) plus key-up (flag 1). `type=long` adds
 * the long-press flag (3). There is no separate key-up or repeat parameter.
 * A press sends one short command. Holding the TV button does not repeat it.
 */
enum class EnigmaKey(
    val code: Int,
    val repeats: Boolean = false,
    val supportsLongPress: Boolean = false,
) {
    UP(103, repeats = true),
    DOWN(108, repeats = true),
    LEFT(105, repeats = true),
    RIGHT(106, repeats = true),
    OK(352),
    EXIT(174),
    MENU(139, supportsLongPress = true),
    INFO(358, supportsLongPress = true),
    EPG(365, supportsLongPress = true),
    CHANNEL_UP(402, repeats = true),
    CHANNEL_DOWN(403, repeats = true),
    RED(398, supportsLongPress = true),
    GREEN(399, supportsLongPress = true),
    YELLOW(400, supportsLongPress = true),
    BLUE(401, supportsLongPress = true),
    PLAY(207),
    PAUSE(119),
    PLAY_PAUSE(164),
    STOP(128),
    RECORD(167),
    NEXT(407),
    PREVIOUS(412),
    FAST_FORWARD(208, repeats = true),
    REWIND(168, repeats = true),
    TEXT(388),
    HELP(138),
    AUDIO(392, supportsLongPress = true),
    TV(377),
    RADIO(385),
    POWER(116),
    DIGIT_0(11),
    DIGIT_1(2),
    DIGIT_2(3),
    DIGIT_3(4),
    DIGIT_4(5),
    DIGIT_5(6),
    DIGIT_6(7),
    DIGIT_7(8),
    DIGIT_8(9),
    DIGIT_9(10),
}

enum class KeyAction {
    SHORT,
    LONG,
}
