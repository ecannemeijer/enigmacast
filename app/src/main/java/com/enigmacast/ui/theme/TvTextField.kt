package com.enigmacast.ui.theme

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.enigmacast.R

/** What a [TvTextField] holds; picks the keyboard and how the value is shown. */
enum class TvFieldKind {
    TEXT,

    /** IP address or host name: URI keyboard (digits, `.` and `/`). */
    HOST,

    /** Port numbers: number keyboard. */
    NUMBER,

    /** Shown as dots, password keyboard. */
    PASSWORD,

    /** Several lines (WireGuard config): Enter types a new line. */
    MULTILINE,
}

/**
 * Text field for the remote. Moving onto it only highlights it (no cursor, no keyboard) and
 * the arrows move on as for any button; OK starts editing and opens the right keyboard; the
 * keyboard's Done, OK or one Back ends editing and leaves the highlight on the same field, so
 * Back never leaves the screen while typing. The value is written on every keystroke, as before.
 * See [TvFieldKeys] for the exact keys; Back also goes through [BackHandler] for predictive back. [large] is the connection-screen size; otherwise the
 * compact Settings size.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TvTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    kind: TvFieldKind = TvFieldKind.TEXT,
    large: Boolean = false,
    visualTransformation: VisualTransformation? = null,
    monospace: Boolean = false,
    browsable: Boolean = true,
    fieldFocus: FocusRequester? = null,
    leftFocus: FocusRequester? = null,
    rightFocus: FocusRequester? = null,
    followEdge: Boolean = false,
    /**
     * Start typing at once: focus in the text and the keyboard shown when the field appears.
     * Only for an explicit search action (Torrent › Search); fields otherwise open on OK.
     */
    startEditing: Boolean = false,
) {
    var editing by remember { mutableStateOf(startEditing) }
    var highlighted by remember { mutableStateOf(false) }
    val shell = remember { FocusRequester() }
    val input = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val multiline = kind == TvFieldKind.MULTILINE

    fun stopEditing(move: FocusDirection? = null) {
        if (!editing) return
        keyboard?.hide()
        // Focus goes back to the field's frame first, then the text input stops being focusable.
        runCatching { shell.requestFocus() }
        editing = false
        if (move != null) focusManager.moveFocus(move)
    }

    // Back while typing ends editing instead of leaving the screen. Goes through the activity's
    // back dispatcher, so it also works with predictive back (where Back never arrives as a key).
    BackHandler(enabled = editing) { stopEditing() }

    LaunchedEffect(editing) {
        if (editing) {
            // Waits for the input to be attached (it is not yet when the field starts in editing).
            input.requestWhenReady()
            keyboard?.show()
        }
    }

    // One Back while the keyboard is open goes to the keyboard, which closes itself; end editing then too.
    val imeVisible = WindowInsets.isImeVisible
    var imeSeen by remember { mutableStateOf(false) }
    LaunchedEffect(editing, imeVisible) {
        val step = TvFieldKeys.imeStep(editing, imeVisible, imeSeen)
        imeSeen = step.imeSeen
        if (step.stop) stopEditing()
    }

    val labelSize = if (large) 14.sp else 11.sp
    val textSize = if (large) 20.sp else 14.sp
    val shape = RoundedCornerShape(if (large) 10.dp else 8.dp)
    val active = editing || highlighted
    val edge = if (followEdge) LocalEdgeLeft.current else null
    Column(modifier) {
        // One line whatever happens, so focusing a field never changes its height; the label wins the width.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                color = if (active) EnigmaColors.Accent else EnigmaColors.Muted,
                fontSize = labelSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (active) {
                val hint = when {
                    editing && large -> R.string.tvfield_hint_done
                    editing -> R.string.tvfield_hint_done_short
                    large -> R.string.tvfield_hint_edit
                    else -> R.string.tvfield_hint_edit_short
                }
                Text(
                    stringResource(hint),
                    color = EnigmaColors.Subtle,
                    fontSize = if (large) 12.sp else 10.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp),
                )
            }
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = !multiline,
            minLines = if (multiline) 3 else 1,
            maxLines = if (multiline) 8 else 1,
            textStyle = TextStyle(
                color = EnigmaColors.Text,
                fontSize = textSize,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            ),
            cursorBrush = SolidColor(EnigmaColors.Accent),
            visualTransformation = visualTransformation
                ?: if (kind == TvFieldKind.PASSWORD) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = when (kind) {
                    TvFieldKind.HOST -> KeyboardType.Uri
                    TvFieldKind.NUMBER -> KeyboardType.Number
                    TvFieldKind.PASSWORD -> KeyboardType.Password
                    TvFieldKind.TEXT, TvFieldKind.MULTILINE -> KeyboardType.Text
                },
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { stopEditing() }),
            decorationBox = { inner ->
                // The frame is what the D-pad focuses while browsing; the text input inside only takes focus while editing.
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = if (large) 6.dp else 3.dp)
                        .background(if (editing) EditingBackground else EnigmaColors.Raised, shape)
                        .border(if (active) 2.dp else 1.dp, if (active) EnigmaColors.Accent else IdleBorder, shape)
                        .padding(horizontal = if (large) 16.dp else 10.dp, vertical = if (large) 14.dp else 8.dp),
                ) { inner() }
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequesterIf(true, fieldFocus)
                .focusRequester(shell)
                .focusProperties {
                    canFocus = browsable
                    when {
                        leftFocus != null -> left = leftFocus
                        edge != null -> left = edge
                    }
                    if (rightFocus != null) right = rightFocus
                }
                .onFocusChanged { highlighted = it.isFocused }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown && event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                    val code = event.nativeKeyEvent.keyCode
                    val up = event.type == KeyEventType.KeyUp
                    val action = if (editing) TvFieldKeys.whileEditing(code, up, multiline) else TvFieldKeys.whileBrowsing(code, up)
                    when (action) {
                        TvFieldKeys.Action.NONE -> false
                        TvFieldKeys.Action.CONSUME -> true
                        TvFieldKeys.Action.START_EDIT -> { editing = true; true }
                        TvFieldKeys.Action.STOP_EDIT -> { stopEditing(); true }
                        TvFieldKeys.Action.STOP_AND_MOVE_UP -> { stopEditing(FocusDirection.Up); true }
                        TvFieldKeys.Action.STOP_AND_MOVE_DOWN -> { stopEditing(FocusDirection.Down); true }
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onTap = { editing = true }) }
                .focusable()
                .focusRequester(input)
                .focusProperties { canFocus = editing }
                .onFocusChanged { state -> if (!state.isFocused && editing && !state.hasFocus) editing = false },
        )
    }
}

private val IdleBorder = Color(0xFF3A4458)
private val EditingBackground = Color(0xFF24211A)
