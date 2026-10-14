package com.enigmacast.ui.security

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.data.security.SettingsLock
import com.enigmacast.domain.security.PinCheck
import com.enigmacast.domain.security.PinHash
import com.enigmacast.ui.theme.CompactButton
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.EnigmaType
import com.enigmacast.ui.theme.requestWhenReady
import com.enigmacast.ui.theme.skinShape
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The PIN steps: unlock Settings, or turn the lock on / off / change the PIN. */
enum class PinStep { UNLOCK, NEW, REPEAT, CURRENT_TO_DISABLE, CURRENT_TO_CHANGE }

@HiltViewModel
class SettingsLockViewModel @Inject constructor(val lock: SettingsLock) : ViewModel() {
    var step by mutableStateOf<PinStep?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    private var first: String? = null

    fun start(step: PinStep) { this.step = step; error = null; first = null }
    fun cancel() { step = null; error = null; first = null }

    /** [wrong] / [cooling] / [mismatch] build the localized feedback. */
    fun submit(pin: String, wrong: (Int) -> String, cooling: (Int) -> String, mismatch: String) {
        val now = step ?: return
        when (now) {
            PinStep.NEW -> { first = pin; error = null; step = PinStep.REPEAT; return }
            PinStep.REPEAT -> {
                if (pin != first) { error = mismatch; first = null; step = PinStep.NEW; return }
                busy = true
                viewModelScope.launch { withContext(Dispatchers.Default) { lock.setPin(pin) }; busy = false; cancel() }
                return
            }
            else -> Unit
        }
        busy = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                when (now) {
                    PinStep.UNLOCK -> lock.unlock(pin)
                    PinStep.CURRENT_TO_DISABLE -> lock.disable(pin)
                    else -> lock.check(pin)
                }
            }
            busy = false
            when (result) {
                PinCheck.Ok -> if (now == PinStep.CURRENT_TO_CHANGE) start(PinStep.NEW) else cancel()
                is PinCheck.Wrong -> error = wrong(result.attemptsLeft)
                is PinCheck.CoolingDown -> error = cooling(result.seconds)
            }
        }
    }
}

/** The step's title. */
@Composable
fun pinTitle(step: PinStep): String = stringResource(
    when (step) {
        PinStep.UNLOCK -> R.string.pin_unlock_title
        PinStep.NEW -> R.string.pin_new_title
        PinStep.REPEAT -> R.string.pin_repeat_title
        PinStep.CURRENT_TO_DISABLE, PinStep.CURRENT_TO_CHANGE -> R.string.pin_current_title
    },
)

/**
 * TV PIN pad: four masked dots, a 3×4 number grid (D-pad) and the remote's 0–9 keys,
 * ⌫ to erase, Cancel to leave. The fourth digit submits.
 */
@Composable
fun PinPad(
    title: String,
    error: String?,
    busy: Boolean,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pin by remember(title, error) { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestWhenReady() }
    fun type(d: Char) {
        if (busy || pin.length >= 4) return
        pin += d
        if (pin.length == 4) { val done = pin; pin = ""; onSubmit(done) }
    }
    fun erase() { pin = pin.dropLast(1) }
    Box(modifier.fillMaxSize().background(EnigmaColors.Canvas.copy(alpha = 0.96f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(320.dp)
                .background(EnigmaColors.Selected, skinShape(16.dp))
                .border(1.dp, EnigmaColors.CardBorder, skinShape(16.dp))
                .padding(20.dp)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val code = e.nativeKeyEvent.keyCode
                    when (code) {
                        in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> { type('0' + (code - android.view.KeyEvent.KEYCODE_0)); true }
                        android.view.KeyEvent.KEYCODE_DEL -> { erase(); true }
                        else -> false
                    }
                }
                .focusGroup(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, color = EnigmaColors.Text, fontSize = 18.sp, fontWeight = EnigmaType.Strong)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(4) { i ->
                    Box(Modifier.size(14.dp).background(if (i < pin.length) EnigmaColors.Accent else EnigmaColors.Raised, CircleShape).border(1.dp, EnigmaColors.CardBorder, CircleShape))
                }
            }
            Text(error.orEmpty(), color = EnigmaColors.Danger, fontSize = 13.sp)
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✕")
            keys.chunked(3).forEachIndexed { row, line ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    line.forEachIndexed { col, label ->
                        CompactButton(
                            label = if (label == "✕") stringResource(R.string.cancel) else label,
                            onClick = {
                                when (label) {
                                    "⌫" -> erase()
                                    "✕" -> onCancel()
                                    else -> type(label[0])
                                }
                            },
                            modifier = Modifier
                                .width(80.dp)
                                .then(if (row == 0 && col == 0) Modifier.focusRequester(first) else Modifier)
                                .focusProperties {
                                    if (row == 0) up = FocusRequester.Cancel
                                    if (row == 3) down = FocusRequester.Cancel
                                    if (col == 0) left = FocusRequester.Cancel
                                    if (col == 2) right = FocusRequester.Cancel
                                },
                        )
                    }
                }
            }
        }
    }
}

/** Runs the view model's current step as a full-screen PIN pad (nothing when no step). */
@Composable
fun PinFlow(model: SettingsLockViewModel, onCancel: () -> Unit = {}) {
    val step = model.step ?: return
    val mismatch = stringResource(R.string.pin_mismatch)
    val wrongFmt = stringResource(R.string.pin_wrong)
    val coolFmt = stringResource(R.string.pin_cooldown)
    androidx.activity.compose.BackHandler { model.cancel(); onCancel() }
    PinPad(
        title = pinTitle(step),
        error = model.error,
        busy = model.busy,
        onSubmit = { pin ->
            if (!PinHash.valid(pin)) return@PinPad
            model.submit(pin, { wrongFmt.format(it) }, { coolFmt.format(it) }, mismatch)
        },
        onCancel = { model.cancel(); onCancel() },
    )
}
