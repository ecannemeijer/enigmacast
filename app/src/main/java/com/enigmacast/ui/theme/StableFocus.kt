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

/**
 * Which requesters are attached to a node right now. Compose has no public "attached" check,
 * and asking an unattached requester for focus logs "FocusRequester is not initialized" on
 * every try. [requestWhenAttached] waits for the attachment instead of asking each frame.
 */
object FocusAttach {
    private val counts = java.util.WeakHashMap<FocusRequester, Int>()
    fun isAttached(requester: FocusRequester): Boolean = (counts[requester] ?: 0) > 0
    internal fun attach(requester: FocusRequester) { counts[requester] = (counts[requester] ?: 0) + 1 }
    internal fun detach(requester: FocusRequester) {
        val n = (counts[requester] ?: 0) - 1
        if (n > 0) counts[requester] = n else counts.remove(requester)
    }
}

private class AttachNode(var requester: FocusRequester) : Modifier.Node() {
    override fun onAttach() = FocusAttach.attach(requester)
    override fun onDetach() = FocusAttach.detach(requester)
}

private data class AttachElement(val requester: FocusRequester) : androidx.compose.ui.node.ModifierNodeElement<AttachNode>() {
    override fun create() = AttachNode(requester)
    override fun update(node: AttachNode) {
        if (node.requester === requester) return
        if (node.isAttached) { FocusAttach.detach(node.requester); FocusAttach.attach(requester) }
        node.requester = requester
    }
}

/** [focusRequester] that [requestWhenAttached] can see. */
fun Modifier.trackedFocusRequester(requester: FocusRequester): Modifier =
    this.focusRequester(requester).then(AttachElement(requester))

/** [focusRequesterIf] that [requestWhenAttached] can see. */
fun Modifier.trackedFocusRequesterIf(enabled: Boolean, requester: FocusRequester?): Modifier {
    val target = if (enabled && requester != null) requester else Unused
    return this.focusRequester(target).then(AttachElement(target))
}

/**
 * Focus for a requester attached with [trackedFocusRequester]: waits (at most [maxFrames]
 * frames) until it is attached, and only then asks for focus. Gives up quietly when the
 * target never appears (an empty row, a card that is not composed).
 */
suspend fun FocusRequester.requestWhenAttached(maxFrames: Int = 30): Boolean {
    repeat(maxFrames) {
        androidx.compose.runtime.withFrameNanos { }
        if (FocusAttach.isAttached(this) &&
            runCatching { requestFocus(androidx.compose.ui.focus.FocusDirection.Enter) }.getOrDefault(false)
        ) return true
    }
    return false
}
