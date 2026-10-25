package com.enigmacast.ui.keys

import com.enigmacast.ui.theme.EnigmaType
import com.enigmacast.ui.theme.skinShape
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.model.StreamMode
import com.enigmacast.domain.remote.KeyBinding
import com.enigmacast.domain.remote.KeyBindings
import com.enigmacast.domain.remote.KeyMode
import com.enigmacast.domain.remote.PlayerAction
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.NavigationSoundsForWindow
import com.enigmacast.ui.theme.SettingsCard
import com.enigmacast.ui.theme.TvChoiceDialog
import com.enigmacast.ui.theme.TvConfirmDialog
import com.enigmacast.ui.theme.CompactListRow
import com.enigmacast.ui.theme.LocalEdgeLeft
import com.enigmacast.ui.theme.requestWhenReady
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class RemoteKeysViewModel @Inject constructor(private val settings: SettingsRepository) : ViewModel() {
    val stored: StateFlow<Map<String, List<String>>> = settings.settings.map { it.remote.keyBindings }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    val longPressMs: StateFlow<Long> = settings.settings.map { it.remote.longPressMs }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 500L)

    private fun change(transform: (Map<String, List<String>>) -> Map<String, List<String>>) {
        viewModelScope.launch {
            settings.update { it.copy(remote = it.remote.copy(keyBindings = transform(it.remote.keyBindings))) }
        }
    }

    fun assign(mode: KeyMode, action: PlayerAction, key: KeyBinding) = change { KeyBindings.assign(it, mode, action, key) }
    fun clear(mode: KeyMode, action: PlayerAction) = change { KeyBindings.clear(it, mode, action) }
    fun resetAction(mode: KeyMode, action: PlayerAction) = change { KeyBindings.resetAction(it, mode, action) }
    fun reset(mode: KeyMode) = change { KeyBindings.reset(it, mode) }
}

/** Settings › Remote control keys: per viewing mode, which key does what while watching. */
@Composable
fun RemoteKeysSettingsPanel(contentFocus: FocusRequester, streamMode: StreamMode, model: RemoteKeysViewModel = hiltViewModel()) {
    val stored by model.stored.collectAsStateWithLifecycle()
    val active = if (streamMode == StreamMode.OWN_TUNER) KeyMode.OWN else KeyMode.FOLLOW
    var editing by remember { mutableStateOf<Pair<KeyMode, PlayerAction>?>(null) }
    var capturing by remember { mutableStateOf<Pair<KeyMode, PlayerAction>?>(null) }
    var pending by remember { mutableStateOf<Triple<KeyMode, PlayerAction, KeyBinding>?>(null) }
    var resetMode by remember { mutableStateOf<KeyMode?>(null) }
    val modes = listOf(active) + KeyMode.entries.filter { it != active && it != KeyMode.MENU } + KeyMode.MENU
    modes.forEachIndexed { modeIndex, mode ->
        val bindings = KeyBindings.effective(stored, mode)
        val title = stringResource(mode.title()) +
            if (mode == active) "  ·  " + stringResource(R.string.keys_mode_active) else ""
        SettingsCard(title) {
            Text(
                stringResource(mode.description()),
                color = EnigmaColors.Muted,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            // Two dense columns: a key row is one short line, so a whole mode fits on screen.
            KeyBindings.actions(mode).chunked(2).forEachIndexed { rowIndex, pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pair.forEachIndexed { column, action ->
                        val first = modeIndex == 0 && rowIndex == 0 && column == 0
                        val cell = @Composable {
                            KeyRow(
                                label = stringResource(action.label()),
                                value = keysText(bindings[action].orEmpty()),
                                onClick = { editing = mode to action },
                                modifier = Modifier.weight(1f).let { if (first) it.focusRequester(contentFocus) else it },
                            )
                        }
                        // Left from the right column goes to the left column, not straight to the menu.
                        if (column == 0) cell() else CompositionLocalProvider(LocalEdgeLeft provides null) { cell() }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            KeyRow(
                label = stringResource(R.string.keys_reset_all),
                value = "›",
                onClick = { resetMode = mode },
            )
            Text(
                stringResource(R.string.keys_fixed_note),
                color = EnigmaColors.Muted,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
    editing?.let { (mode, action) ->
        val keys = KeyBindings.effective(stored, mode)[action].orEmpty()
        TvChoiceDialog(
            title = stringResource(action.label()),
            message = keysText(keys),
            choices = listOf(
                stringResource(R.string.keys_assign),
                stringResource(R.string.keys_remove),
                stringResource(R.string.keys_default),
                stringResource(R.string.cancel),
            ),
            onChoose = { choice ->
                editing = null
                when (choice) {
                    0 -> capturing = mode to action
                    1 -> model.clear(mode, action)
                    2 -> model.resetAction(mode, action)
                }
            },
            onDismiss = { editing = null },
            stacked = true,
        )
    }
    capturing?.let { (mode, action) ->
        val longPressMs by model.longPressMs.collectAsStateWithLifecycle()
        KeyCaptureDialog(
            actionLabel = stringResource(action.label()),
            // Outside the player only short presses are used (a held key keeps scrolling lists).
            longPressMs = if (mode == KeyMode.MENU) Long.MAX_VALUE else longPressMs,
            onKey = { key ->
                capturing = null
                val other = KeyBindings.conflict(KeyBindings.effective(stored, mode), action, key)
                if (other != null) pending = Triple(mode, action, key) else model.assign(mode, action, key)
            },
            onCancel = { capturing = null },
        )
    }
    pending?.let { (mode, action, key) ->
        val other = KeyBindings.conflict(KeyBindings.effective(stored, mode), action, key)
        TvConfirmDialog(
            title = stringResource(R.string.keys_conflict_title),
            message = stringResource(
                R.string.keys_conflict_message,
                keysText(listOf(key)),
                other?.let { stringResource(it.label()) }.orEmpty(),
                stringResource(action.label()),
            ),
            confirmLabel = stringResource(R.string.keys_conflict_replace),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                pending = null
                model.assign(mode, action, key)
            },
            onDismiss = { pending = null },
            compact = true,
        )
    }
    resetMode?.let { mode ->
        TvConfirmDialog(
            title = stringResource(R.string.keys_reset_all),
            message = stringResource(R.string.keys_reset_message),
            confirmLabel = stringResource(R.string.keys_reset_confirm),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                resetMode = null
                model.reset(mode)
            },
            onDismiss = { resetMode = null },
            compact = true,
        )
    }
}

/** One dense key assignment: action on the left, its keys on the right. OK edits. */
@Composable
private fun KeyRow(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    CompactListRow(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) { _ ->
        Text(label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(value, fontSize = 12.sp, fontWeight = EnigmaType.Strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * "Press a key": the next key pressed (and released) is taken. Held for the long-press time it
 * counts as a long press. Back cancels; OK, Back and the digits keep their fixed meaning.
 */
@Composable
private fun KeyCaptureDialog(actionLabel: String, longPressMs: Long, onKey: (KeyBinding) -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        NavigationSoundsForWindow()
        val focus = remember { FocusRequester() }
        var downCode by remember { mutableStateOf<Int?>(null) }
        var downAt by remember { mutableLongStateOf(0L) }
        var fixed by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { focus.requestWhenReady() }
        Column(
            modifier = Modifier
                .widthIn(min = 260.dp, max = 420.dp)
                .background(EnigmaColors.Selected, skinShape(10.dp))
                .border(1.dp, EnigmaColors.CardBorder, skinShape(10.dp))
                .padding(16.dp)
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    val code = native.keyCode
                    when (event.type) {
                        KeyEventType.KeyDown -> if (native.repeatCount == 0) {
                            downCode = code
                            downAt = native.eventTime
                        }
                        KeyEventType.KeyUp -> {
                            // The release of the OK press that opened this dialog has no press here.
                            // Other keys (colour keys on some remotes arrive as a lone release) count anyway.
                            if (downCode != code && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                                return@onPreviewKeyEvent true
                            }
                            if (downCode != code) downAt = native.downTime
                            downCode = null
                            when {
                                code == KeyEvent.KEYCODE_BACK -> onCancel()
                                KeyBindings.isFixed(code) -> fixed = true
                                else -> onKey(KeyBinding(code, long = native.eventTime - downAt >= longPressMs))
                            }
                        }
                        else -> Unit
                    }
                    true
                }
                .focusable(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(actionLabel, color = EnigmaColors.Text, fontSize = 16.sp, fontWeight = EnigmaType.Strong)
            Text(stringResource(R.string.keys_press_now), color = EnigmaColors.Text, fontSize = 14.sp)
            Text(stringResource(R.string.keys_press_hint), color = EnigmaColors.Muted, fontSize = 12.sp, lineHeight = 16.sp)
            if (fixed) Text(stringResource(R.string.keys_fixed_key), color = EnigmaColors.Accent, fontSize = 12.sp)
        }
    }
}

internal fun PlayerAction.label(): Int = when (this) {
    PlayerAction.CHANNEL_UP -> R.string.key_action_channel_up
    PlayerAction.CHANNEL_DOWN -> R.string.key_action_channel_down
    PlayerAction.CHANNEL_LIST -> R.string.key_action_channel_list
    PlayerAction.INFO -> R.string.key_action_info
    PlayerAction.BANNER -> R.string.key_action_banner
    PlayerAction.OPTIONS -> R.string.key_action_options
    PlayerAction.GUIDE -> R.string.key_action_guide
    PlayerAction.SEARCH -> R.string.key_action_search
    PlayerAction.RECORD -> R.string.key_action_record
    PlayerAction.PAUSE -> R.string.key_action_pause
    PlayerAction.AUDIO_TRACK -> R.string.key_action_audio
    PlayerAction.SUBTITLES -> R.string.key_action_subtitles
    PlayerAction.PICTURE_MODE -> R.string.key_action_picture
    PlayerAction.SWITCH_RECEIVER -> R.string.switch_receiver
    PlayerAction.PREVIOUS_CHANNEL -> R.string.key_action_previous_channel
}

private fun KeyMode.title(): Int = when (this) {
    KeyMode.OWN -> R.string.keys_mode_own
    KeyMode.FOLLOW -> R.string.keys_mode_follow
    KeyMode.MENU -> R.string.keys_mode_menu
}

private fun KeyMode.description(): Int = when (this) {
    KeyMode.OWN -> R.string.keys_mode_own_desc
    KeyMode.FOLLOW -> R.string.keys_mode_follow_desc
    KeyMode.MENU -> R.string.keys_mode_menu_desc
}

@Composable
private fun keysText(keys: List<KeyBinding>): String {
    if (keys.isEmpty()) return stringResource(R.string.keys_none)
    val hold = stringResource(R.string.keys_hold)
    return keys.joinToString(", ") { key -> keyName(key.keyCode) + if (key.long) " ($hold)" else "" }
}

internal fun keyName(code: Int): String = when (code) {
    KeyEvent.KEYCODE_DPAD_UP -> "▲"
    KeyEvent.KEYCODE_DPAD_DOWN -> "▼"
    KeyEvent.KEYCODE_DPAD_LEFT -> "◀"
    KeyEvent.KEYCODE_DPAD_RIGHT -> "▶"
    KeyEvent.KEYCODE_CHANNEL_UP -> "CH+"
    KeyEvent.KEYCODE_CHANNEL_DOWN -> "CH−"
    KeyEvent.KEYCODE_PAGE_UP -> "Page ▲"
    KeyEvent.KEYCODE_PAGE_DOWN -> "Page ▼"
    KeyEvent.KEYCODE_PROG_RED -> "Red"
    KeyEvent.KEYCODE_PROG_GREEN -> "Green"
    KeyEvent.KEYCODE_PROG_YELLOW -> "Yellow"
    KeyEvent.KEYCODE_PROG_BLUE -> "Blue"
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "Play/Pause"
    KeyEvent.KEYCODE_MEDIA_RECORD -> "Rec"
    KeyEvent.KEYCODE_MEDIA_NEXT -> "⏭"
    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "⏮"
    KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> "Audio"
    KeyEvent.KEYCODE_CAPTIONS -> "Subtitles"
    else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").replace('_', ' ')
        .lowercase().replaceFirstChar { it.uppercase() }
}
