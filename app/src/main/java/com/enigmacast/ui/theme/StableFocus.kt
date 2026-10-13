package com.enigmacast.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

/** Attached where a conditional requester is off; never asked for focus. */
private val Unused = FocusRequester()

/**
 * Attaches [requester] when [enabled], without changing the modifier chain's shape.
 *
 * `then(if (c) Modifier.focusRequester(r) else Modifier)` adds or drops a node when `c` flips.
 * The modifiers after it then sit at other positions, so Compose may rebuild them, including
 * the focus target of a focused item. Compose 1.12.1 then clears the window focus during
 * applyChanges and re-enters the tree on a node that is already detached:
 * "IllegalStateException: visitAncestors called on an unattached node" (Settings › Receivers).
 * Here the node always exists; only the requester it points at changes.
 */
fun Modifier.focusRequesterIf(enabled: Boolean, requester: FocusRequester?): Modifier =
    this.focusRequester(if (enabled && requester != null) requester else Unused)
