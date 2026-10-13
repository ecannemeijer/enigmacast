package com.enigmacast.ui.receivers

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.data.openwebif.OpenWebifApi
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.model.ReceiverList
import com.enigmacast.live.OwnTunerSession
import com.enigmacast.live.ReceiverNames
import com.enigmacast.live.ReceiverSwitcher
import com.enigmacast.sync.SyncScheduler
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.NavigationSoundsForWindow
import com.enigmacast.ui.theme.focusRequesterIf
import com.enigmacast.ui.theme.requestWhenReady
import com.enigmacast.util.EnigmaLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class Reach { CHECKING, ONLINE, OFFLINE }

@HiltViewModel
class ReceiverSwitchViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val api: OpenWebifApi,
    private val names: ReceiverNames,
    private val ownTuner: OwnTunerSession,
    private val switcher: ReceiverSwitcher,
) : ViewModel() {
    val open: StateFlow<Boolean> = switcher.open
    val receivers: StateFlow<List<ReceiverConfig>> = settings.settings.map { it.receivers }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val activeId: StateFlow<String?> = settings.settings.map { it.activeReceiver()?.id }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val labels: StateFlow<Map<String, String>> = names.labels
    val reach = mutableStateMapOf<String, Reach>()

    /** Asks every box `/api/about` (short timeout) for the status dot and its brand/model. */
    fun check() {
        receivers.value.forEach { receiver ->
            names.ensure(receiver)
            reach[receiver.id] = Reach.CHECKING
            viewModelScope.launch {
                val ok = withTimeoutOrNull(4_000) { runCatching { api.about(receiver) }.isSuccess } == true
                reach[receiver.id] = if (ok) Reach.ONLINE else Reach.OFFLINE
            }
        }
    }

    fun close() = switcher.hide()

    /**
     * Makes [id] the active box: this device's stream on the old box is dropped first (its tuner
     * is freed), then the player, guide, bouquets and Home follow the new receiver, and a sync
     * fills the cache for that box.
     */
    fun choose(id: String) = switcher.switchTo(id)
}

/** Compact list of the receivers: status dot, name, brand/model and address; the current one is checked. */
@Composable
fun ReceiverSwitchHost(onAddReceiver: () -> Unit, model: ReceiverSwitchViewModel = hiltViewModel()) {
    val open by model.open.collectAsStateWithLifecycle()
    if (!open) return
    val receivers by model.receivers.collectAsStateWithLifecycle()
    val activeId by model.activeId.collectAsStateWithLifecycle()
    val labels by model.labels.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { model.check() }
    Dialog(onDismissRequest = model::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        NavigationSoundsForWindow()
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { first.requestWhenReady() }
        Column(
            Modifier
                .widthIn(min = 300.dp, max = 460.dp)
                .background(EnigmaColors.Selected, RoundedCornerShape(10.dp))
                .border(1.dp, EnigmaColors.CardBorder, RoundedCornerShape(10.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.switch_receiver), color = EnigmaColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (receivers.size <= 1) {
                Text(stringResource(R.string.switch_receiver_only_one), color = EnigmaColors.Muted, fontSize = 12.sp, lineHeight = 16.sp)
            }
            val focusIndex = receivers.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
            receivers.forEachIndexed { index, receiver ->
                val current = receiver.id == activeId
                val dot = when (model.reach[receiver.id]) {
                    Reach.ONLINE -> EnigmaColors.Success
                    Reach.OFFLINE -> EnigmaColors.Danger
                    else -> EnigmaColors.Accent
                }
                val label = labels[receiver.id]?.takeIf { it.isNotBlank() && it != receiver.name.trim() }
                ListItem(
                    selected = current,
                    onClick = { model.choose(receiver.id) },
                    modifier = Modifier.fillMaxWidth().focusRequesterIf(index == focusIndex, first),
                    leadingContent = { Box(Modifier.size(10.dp).background(dot, CircleShape)) },
                    headlineContent = {
                        Text(
                            listOfNotNull(receiver.name.trim().ifBlank { null }, label).joinToString("  ·  "),
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = { Text(receiver.host, fontSize = 12.sp, maxLines = 1) },
                    trailingContent = { if (current) Text("✓", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                    scale = ListItemDefaults.scale(scale = 1f, focusedScale = 1.02f),
                )
            }
            ListItem(
                selected = false,
                onClick = {
                    model.close()
                    onAddReceiver()
                },
                modifier = Modifier.fillMaxWidth().focusRequesterIf(receivers.isEmpty(), first),
                headlineContent = { Text("+  " + stringResource(R.string.switch_receiver_add), fontSize = 14.sp) },
                scale = ListItemDefaults.scale(scale = 1f, focusedScale = 1.02f),
            )
        }
    }
}
