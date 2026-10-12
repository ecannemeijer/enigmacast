package com.enigmacast.ui.soccer

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer

/**
 * Vertical focus chain for a TV page made of sections (hero, rows, tables).
 *
 * Each section is a focus group with a restorer: entering it lands on the item that had focus
 * last (else its first item). When focus would leave a section:
 * - Down / Up go to the next / previous section in [keys] order, never to whatever the 2D
 *   search finds nearest (that was the nav menu on the left);
 * - at the top or bottom the move is cancelled, so focus stays on the page;
 * - Right is cancelled (nothing to the right of a section);
 * - Left is allowed: only the leftmost item has nothing left of it inside its section, and its
 *   own `focusProperties { left = menu }` sends it to the nav.
 */
class FocusChain(private val requesters: MutableMap<String, FocusRequester>) {
    var keys: List<String> = emptyList()

    fun requester(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    internal fun neighbourKey(key: String, step: Int): String? = targetKey(keys, key, step)

    companion object {
        private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

        /** The section one [step] away from [key] in page order; null at either end (focus stays). */
        fun targetKey(keys: List<String>, key: String, step: Int): String? =
            keys.indexOf(key).takeIf { it >= 0 }?.let { keys.getOrNull(it + step) }
    }

    /** Focus the first section (e.g. when the page is entered from the menu). */
    fun first(): FocusRequester? = keys.firstOrNull()?.let(::requester)

    /**
     * Requesting the next section from inside onExit moved the focus while the first move was
     * still being processed: the section being left saw a second exit with the same direction
     * and passed it on again, so a row was skipped. The request now runs after the current focus
     * change, and only one move is in flight at a time.
     */
    private var moving = false

    private fun move(key: String, step: Int) {
        if (moving) return
        val target = targetKey(keys, key, step) ?: return
        moving = true
        mainHandler.post {
            // A restorer whose remembered card was recycled (scrolled-away LazyRow item) can refuse:
            // then enter the section in the direction of travel, landing on its first card.
            val r = requester(target)
            val ok = runCatching { r.requestFocus() }.getOrDefault(false)
            if (!ok) runCatching { r.requestFocus(if (step > 0) FocusDirection.Down else FocusDirection.Up) }
            moving = false
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    fun section(key: String): Modifier = Modifier
        .focusProperties {
            onExit = {
                when (requestedFocusDirection) {
                    FocusDirection.Down -> { cancelFocusChange(); move(key, 1) }
                    FocusDirection.Up -> { cancelFocusChange(); move(key, -1) }
                    FocusDirection.Right -> cancelFocusChange()
                    else -> Unit
                }
            }
        }
        .focusRequester(requester(key))
        .focusRestorer()
        .focusGroup()
}

@Composable
fun rememberFocusChain(): FocusChain = remember { FocusChain(HashMap()) }

/** Same, for a full-screen dialog: Left is cancelled too (there is no menu behind it). */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.trapFocus(): Modifier = this
    .focusProperties {
        onExit = {
            when (requestedFocusDirection) {
                FocusDirection.Up, FocusDirection.Down, FocusDirection.Left, FocusDirection.Right -> cancelFocusChange()
                else -> Unit
            }
        }
    }
    .focusGroup()
