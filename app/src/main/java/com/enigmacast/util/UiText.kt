package com.enigmacast.util

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * User-visible text built outside Compose (view models, sessions, domain helpers). It
 * keeps the string resource and its arguments and is turned into text by the UI with the
 * activity's context, so it follows the app language chosen in Settings. Arguments that
 * are themselves [UiText] are resolved too.
 */
sealed interface UiText {
    fun resolve(context: Context): String

    /** Text that is not translated: receiver data, names, raw system error messages. */
    data class Raw(val value: String) : UiText {
        override fun resolve(context: Context): String = value
    }

    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText {
        override fun resolve(context: Context): String {
            return if (args.isEmpty()) context.getString(id) else context.getString(id, *resolveArgs(context, args))
        }
    }

    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = emptyList()) : UiText {
        override fun resolve(context: Context): String {
            return context.resources.getQuantityString(id, count, *resolveArgs(context, args))
        }
    }

    /** Several texts shown together, e.g. a message followed by a list of tuner users. */
    data class Joined(val parts: List<UiText>, val separator: String = "\n") : UiText {
        override fun resolve(context: Context): String = parts.joinToString(separator) { it.resolve(context) }
    }

    companion object {
        val Empty: UiText = Raw("")

        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())

        fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): UiText = Plural(id, count, args.toList())
    }
}

private fun resolveArgs(context: Context, args: List<Any>): Array<Any> =
    args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray()

fun UiText.isEmpty(): Boolean = when (this) {
    is UiText.Raw -> value.isEmpty()
    is UiText.Joined -> parts.all { it.isEmpty() }
    else -> false
}

fun UiText.isNotEmpty(): Boolean = !isEmpty()

@Composable
fun UiText.asString(): String = resolve(LocalContext.current)

/** An error that carries its own translated message (see [uiMessage]). */
interface HasUiText {
    val uiText: UiText
}

/** An I/O failure the app raises itself, with a translated message for the screen. */
class UiTextException(override val uiText: UiText, message: String) : java.io.IOException(message), HasUiText

/**
 * The message to show for a failure: the app's own translated text when it has one,
 * otherwise the system's message as-is (not translated), otherwise [fallback].
 */
fun Throwable.uiMessage(fallback: UiText): UiText {
    (this as? HasUiText)?.let { return it.uiText }
    val text = message?.takeIf { it.isNotBlank() } ?: return fallback
    return UiText.Raw(text)
}
