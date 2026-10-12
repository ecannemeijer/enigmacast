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

    internal fun neighbourKey(key: String, step: Int): String? = keys.indexOf(key).takeIf { it >= 0 }?.let { keys.getOrNull(it + step) }

    private fun neighbour(key: String, step: Int): FocusRequester? {
        val i = keys.indexOf(key)
        if (i < 0) return null
        return keys.getOrNull(i + step)?.let(::requester)
    }

    /** Focus the first section (e.g. when the page is entered from the menu). */
    fun first(): FocusRequester? = keys.firstOrNull()?.let(::requester)

    @OptIn(ExperimentalComposeUiApi::class)
    fun section(key: String): Modifier = Modifier
        .focusProperties {
            onExit = {
                when (requestedFocusDirection) {
                    FocusDirection.Down -> { cancelFocusChange(); neighbour(key, 1)?.requestFocus() }
                    FocusDirection.Up -> { cancelFocusChange(); neighbour(key, -1)?.requestFocus() }
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
