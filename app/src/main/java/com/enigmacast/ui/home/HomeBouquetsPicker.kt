package com.enigmacast.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.enigmacast.data.db.BouquetRow
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.sync.HomeBouquets
import com.enigmacast.live.HomeFilmsRepository
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.NavigationSoundsForWindow
import com.enigmacast.ui.theme.ToggleRow
import com.enigmacast.ui.theme.TvButton
import com.enigmacast.ui.theme.ValueRow
import com.enigmacast.ui.theme.requestWhenReady
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class BouquetChoice(val receiverKey: String, val bouquets: List<BouquetRow>, val selected: Set<String>)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeBouquetsViewModel @Inject constructor(
    private val repository: HomeFilmsRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    val choice: StateFlow<BouquetChoice?> = settingsRepository.settings
        .map { it.activeReceiver()?.let(repository::receiverKey) }
        .flatMapLatest { key ->
            if (key == null) flowOf(null) else repository.bouquetChoice(key).map { (list, selected) -> BouquetChoice(key, list, selected) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun toggle(reference: String) {
        val c = choice.value ?: return
        repository.setBouquets(c.receiverKey, HomeBouquets.toggle(c.selected, reference, c.bouquets.map { it.reference }))
    }

    fun selectAll() {
        val c = choice.value ?: return
        repository.setBouquets(c.receiverKey, null)
    }

    fun deselectAll() {
        val c = choice.value ?: return
        repository.setBouquets(c.receiverKey, emptySet())
    }
}

/** Settings › General › Home page › Bouquets for Home: a row showing "X of Y", OK opens the picker. */
@Composable
fun HomeBouquetsRow(model: HomeBouquetsViewModel = hiltViewModel()) {
    val choice by model.choice.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val c = choice
    val value = if (c == null || c.bouquets.isEmpty()) stringResource(R.string.home_bouquets_none_cached)
    else stringResource(R.string.home_bouquets_count, c.selected.size, c.bouquets.size)
    ValueRow(
        title = stringResource(R.string.home_bouquets),
        value = value,
        description = stringResource(R.string.home_bouquets_desc),
        onClick = { if (c != null && c.bouquets.isNotEmpty()) open = true },
    )
    if (open && c != null) {
        HomeBouquetsDialog(c, model, onClose = { open = false })
    }
}

@Composable
private fun HomeBouquetsDialog(choice: BouquetChoice, model: HomeBouquetsViewModel, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        NavigationSoundsForWindow()
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { first.requestWhenReady() }
        Column(
            Modifier
                .width(520.dp)
                .heightIn(max = 480.dp)
                .background(EnigmaColors.Selected, RoundedCornerShape(14.dp))
                .border(1.dp, EnigmaColors.CardBorder, RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.home_bouquets), color = EnigmaColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.home_bouquets_count, choice.selected.size, choice.bouquets.size),
                color = EnigmaColors.Muted,
                fontSize = 13.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton(stringResource(R.string.home_bouquets_all), model::selectAll, Modifier.focusRequester(first))
                TvButton(stringResource(R.string.home_bouquets_none), model::deselectAll)
                TvButton(stringResource(R.string.home_close), onClose)
            }
            LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(choice.bouquets, key = { _, b -> b.reference }) { _, bouquet ->
                    ToggleRow(
                        title = bouquet.name.ifBlank { bouquet.reference },
                        checked = bouquet.reference in choice.selected,
                        onCheckedChange = { model.toggle(bouquet.reference) },
                    )
                }
            }
        }
    }
}
