package com.enigmacast.domain.remote

/** MENU is everything outside the player (Home, guide, settings). The two playback screens: this device's own tuner stream, or following the receiver's picture. */
enum class KeyMode { OWN, FOLLOW, MENU }

/**
 * App actions a remote key can be assigned to. Following the receiver, keys that are not
 * assigned to an app action still go to the receiver as before. Back, OK, the arrows in lists
 * and the digits keep their fixed meaning.
 */
enum class PlayerAction(val own: Boolean, val follow: Boolean, val menu: Boolean = false) {
    CHANNEL_UP(own = true, follow = false),
    CHANNEL_DOWN(own = true, follow = false),
    CHANNEL_LIST(own = true, follow = false),
    INFO(own = true, follow = false),
    BANNER(own = true, follow = false),
    OPTIONS(own = true, follow = true),
    GUIDE(own = true, follow = true),
    SEARCH(own = true, follow = true),
    RECORD(own = true, follow = true),
    PAUSE(own = true, follow = false),
    AUDIO_TRACK(own = true, follow = true),
    SUBTITLES(own = true, follow = true),
    PICTURE_MODE(own = true, follow = true),
    SWITCH_RECEIVER(own = true, follow = true, menu = true),
    ;

    fun available(mode: KeyMode) = when (mode) {
        KeyMode.OWN -> own
        KeyMode.FOLLOW -> follow
        KeyMode.MENU -> menu
    }
}

/** One key, short press or held ([long]). */
data class KeyBinding(val keyCode: Int, val long: Boolean = false) {
    fun encode(): String = if (long) "${keyCode}L" else keyCode.toString()

    companion object {
        fun decode(text: String): KeyBinding? {
            val long = text.endsWith("L")
            val code = text.removeSuffix("L").toIntOrNull() ?: return null
            return KeyBinding(code, long)
        }
    }
}

typealias Bindings = Map<PlayerAction, List<KeyBinding>>

object KeyBindings {
    // Android KeyEvent codes (kept here so the rules are plain JVM code and unit-testable).
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val BACK = 4
    const val ENTER = 66
    const val NUMPAD_ENTER = 160
    const val MENU = 82
    const val SEARCH = 84
    const val MEDIA_PLAY_PAUSE = 85
    const val MEDIA_NEXT = 87
    const val MEDIA_PREVIOUS = 88
    const val PAGE_UP = 92
    const val PAGE_DOWN = 93
    const val MEDIA_PLAY = 126
    const val MEDIA_PAUSE = 127
    const val MEDIA_RECORD = 130
    const val INFO = 165
    const val CHANNEL_UP = 166
    const val CHANNEL_DOWN = 167
    const val TV = 170
    const val GUIDE = 172
    const val CAPTIONS = 175
    const val PROG_RED = 183
    const val PROG_GREEN = 184
    const val PROG_YELLOW = 185
    const val PROG_BLUE = 186
    const val MEDIA_AUDIO_TRACK = 222
    const val TV_CONTENTS_MENU = 256
    const val TV_MEDIA_CONTEXT_MENU = 257

    private fun k(code: Int, long: Boolean = false) = KeyBinding(code, long)

    fun defaults(mode: KeyMode): Bindings = when (mode) {
        KeyMode.OWN -> mapOf(
            PlayerAction.CHANNEL_UP to listOf(k(DPAD_UP), k(CHANNEL_UP), k(PAGE_UP), k(MEDIA_NEXT)),
            PlayerAction.CHANNEL_DOWN to listOf(k(DPAD_DOWN), k(CHANNEL_DOWN), k(PAGE_DOWN), k(MEDIA_PREVIOUS)),
            PlayerAction.CHANNEL_LIST to listOf(k(DPAD_LEFT), k(MENU), k(TV), k(TV_CONTENTS_MENU), k(TV_MEDIA_CONTEXT_MENU)),
            PlayerAction.INFO to listOf(k(INFO), k(GUIDE)),
            PlayerAction.BANNER to listOf(k(DPAD_RIGHT)),
            PlayerAction.OPTIONS to listOf(k(PROG_BLUE)),
            PlayerAction.GUIDE to emptyList(),
            PlayerAction.SEARCH to listOf(k(SEARCH)),
            PlayerAction.RECORD to listOf(k(MEDIA_RECORD)),
            PlayerAction.PAUSE to listOf(k(MEDIA_PLAY_PAUSE), k(MEDIA_PLAY), k(MEDIA_PAUSE)),
            PlayerAction.AUDIO_TRACK to listOf(k(MEDIA_AUDIO_TRACK)),
            PlayerAction.SUBTITLES to listOf(k(CAPTIONS)),
            PlayerAction.PICTURE_MODE to emptyList(),
            // Red is free in the own-tuner player: it opens Switch receiver, as on Home.
            PlayerAction.SWITCH_RECEIVER to listOf(k(PROG_RED)),
        )
        KeyMode.FOLLOW -> mapOf(
            // Info goes to the receiver; holding it opens the app's options bar (as before).
            PlayerAction.OPTIONS to listOf(k(INFO, long = true)),
            PlayerAction.GUIDE to emptyList(),
            PlayerAction.SEARCH to listOf(k(SEARCH)),
            PlayerAction.RECORD to emptyList(),
            PlayerAction.AUDIO_TRACK to emptyList(),
            PlayerAction.SUBTITLES to listOf(k(CAPTIONS)),
            PlayerAction.PICTURE_MODE to emptyList(),
            // Red belongs to the receiver while following it; Switch receiver can be assigned.
            PlayerAction.SWITCH_RECEIVER to emptyList(),
        )
        // Outside the player: Red opens Switch receiver.
        KeyMode.MENU -> mapOf(PlayerAction.SWITCH_RECEIVER to listOf(k(PROG_RED)))
    }

    /** Keys that keep their fixed meaning and cannot be assigned. */
    fun isFixed(keyCode: Int): Boolean =
        keyCode == BACK || keyCode == DPAD_CENTER || keyCode == ENTER || keyCode == NUMPAD_ENTER ||
            keyCode in 7..16 || keyCode in 144..153

    private fun storeKey(mode: KeyMode, action: PlayerAction) = "${mode.name}.${action.name}"

    fun actions(mode: KeyMode): List<PlayerAction> = PlayerAction.entries.filter { it.available(mode) }

    fun effective(stored: Map<String, List<String>>, mode: KeyMode): Bindings {
        val defaults = defaults(mode)
        return actions(mode).associateWith { action ->
            stored[storeKey(mode, action)]?.mapNotNull(KeyBinding::decode) ?: defaults[action].orEmpty()
        }
    }

    fun lookup(bindings: Bindings, keyCode: Int, long: Boolean): PlayerAction? =
        bindings.entries.firstOrNull { (_, keys) -> keys.any { it.keyCode == keyCode && it.long == long } }?.key

    /** True when holding this key does something, so a short press must wait for the release. */
    fun hasLong(bindings: Bindings, keyCode: Int): Boolean =
        bindings.values.any { keys -> keys.any { it.keyCode == keyCode && it.long } }

    /** Another action that already uses [binding], if any. */
    fun conflict(bindings: Bindings, action: PlayerAction, binding: KeyBinding): PlayerAction? =
        bindings.entries.firstOrNull { (other, keys) -> other != action && binding in keys }?.key

    /** [action] gets only [binding]; any other action loses it, so a key never does two things. */
    fun assign(stored: Map<String, List<String>>, mode: KeyMode, action: PlayerAction, binding: KeyBinding): Map<String, List<String>> {
        val current = effective(stored, mode)
        val out = stored.toMutableMap()
        current.forEach { (other, keys) ->
            if (other != action && binding in keys) out[storeKey(mode, other)] = (keys - binding).map { it.encode() }
        }
        out[storeKey(mode, action)] = listOf(binding.encode())
        return out
    }

    fun clear(stored: Map<String, List<String>>, mode: KeyMode, action: PlayerAction): Map<String, List<String>> =
        stored + (storeKey(mode, action) to emptyList())

    fun resetAction(stored: Map<String, List<String>>, mode: KeyMode, action: PlayerAction): Map<String, List<String>> {
        // The default keys come back; another action that took one of them gives it up.
        var out = stored - storeKey(mode, action)
        val restored = defaults(mode)[action].orEmpty()
        val current = effective(out, mode)
        current.forEach { (other, keys) ->
            if (other != action && keys.any { it in restored }) out = out + (storeKey(mode, other) to (keys - restored.toSet()).map { it.encode() })
        }
        return out
    }

    fun reset(stored: Map<String, List<String>>, mode: KeyMode): Map<String, List<String>> =
        stored.filterKeys { !it.startsWith("${mode.name}.") }
}
