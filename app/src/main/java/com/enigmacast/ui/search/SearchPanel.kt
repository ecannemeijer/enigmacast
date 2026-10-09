package com.enigmacast.ui.search

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.search.SearchHit
import com.enigmacast.domain.search.SearchKind
import com.enigmacast.domain.search.SearchQuery
import com.enigmacast.domain.search.SearchRows
import com.enigmacast.ui.theme.CompactListRow
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.EnigmaIcons
import com.enigmacast.ui.theme.TvTextField
import com.enigmacast.ui.theme.requestWhenReady
import com.enigmacast.util.asString
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val WhenFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM  HH:mm")

/**
 * One search field and the same dense rows as the channel list.
 * [menuFocus] is the dashboard item to the left; the OSD overlay leaves it unset.
 * [rowsFocusable] stays false until the user moves in from the menu, so the field
 * does not take the highlight by itself.
 */
@Composable
fun SearchPanel(
    rowsFocusable: Boolean,
    onPlayed: () -> Unit,
    onBouquet: (BouquetEntry) -> Unit,
    onPrepare: () -> Unit = {},
    modifier: Modifier = Modifier,
    menuFocus: FocusRequester? = null,
    focusToken: Int = 0,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val fieldFocus = remember { FocusRequester() }
    val micFocus = remember { FocusRequester() }
    val context = LocalContext.current
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) viewModel.onQuery(spoken)
    }
    val listen = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.search_speak))
        }
        runCatching { speech.launch(intent) }
        Unit
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen()
    }
    val startVoice = {
        when (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)) {
            PackageManager.PERMISSION_GRANTED -> listen()
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(rowsFocusable, focusToken) {
        if (!rowsFocusable) return@LaunchedEffect
        fieldFocus.requestWhenReady()
    }
    val nowSec = System.currentTimeMillis() / 1000
    val searchable = SearchQuery.parse(viewModel.query, nowSec).isSearchable
    val sections = viewModel.results.sections()
    Column(
        modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || event.key != Key.Search) return@onPreviewKeyEvent false
                startVoice()
                true
            },
    ) {
        Text(
            stringResource(R.string.nav_search),
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TvTextField(
                label = stringResource(R.string.nav_search),
                value = viewModel.query,
                onValueChange = viewModel::onQuery,
                browsable = rowsFocusable,
                fieldFocus = fieldFocus,
                leftFocus = menuFocus ?: FocusRequester.Cancel,
                rightFocus = micFocus,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = startVoice,
                modifier = Modifier
                    .size(40.dp)
                    .focusRequester(micFocus)
                    .focusProperties {
                        canFocus = rowsFocusable
                        left = fieldFocus
                        if (menuFocus == null) right = FocusRequester.Cancel
                    },
                enabled = rowsFocusable,
                contentPadding = PaddingValues(0.dp),
                scale = ButtonDefaults.scale(scale = 1f, focusedScale = 1.06f),
                colors = ButtonDefaults.colors(
                    containerColor = EnigmaColors.Raised,
                    contentColor = EnigmaColors.Text,
                    focusedContainerColor = EnigmaColors.Accent,
                    focusedContentColor = EnigmaColors.OnAccent,
                ),
            ) {
                Icon(EnigmaIcons.Mic, contentDescription = stringResource(R.string.search_mic), modifier = Modifier.size(18.dp))
            }
        }
        val message = viewModel.message.asString()
        if (message.isNotBlank()) {
            Text(message, color = EnigmaColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        when {
            !searchable -> Hint(stringResource(R.string.search_hint))
            viewModel.searching && viewModel.results.isEmpty -> SearchSpinner()
            !viewModel.searching && viewModel.results.isEmpty -> Hint(stringResource(R.string.search_empty))
            else -> {
                if (viewModel.searching) SearchSpinner()
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    sections.forEach { (kind, hits) ->
                        item(key = "header-$kind") {
                            Text(
                                sectionTitle(kind),
                                color = EnigmaColors.Accent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp),
                            )
                        }
                        items(hits, key = { it.rowKey() }) { hit ->
                            CompactListRow(
                                onClick = { viewModel.choose(hit, onPlayed, onBouquet, onPrepare) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
                                modifier = Modifier
                                    .focusProperties {
                                        canFocus = rowsFocusable
                                        left = menuFocus ?: FocusRequester.Cancel
                                        if (menuFocus == null) right = FocusRequester.Cancel
                                    },
                            ) {
                                Column {
                                    Text(SearchRows.headline(hit), color = EnigmaColors.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val subtitle = detail(hit)
                                    if (subtitle.isNotBlank()) {
                                        Text(subtitle, color = EnigmaColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSpinner() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        CircularProgressIndicator(color = EnigmaColors.Accent, strokeWidth = 3.dp, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = EnigmaColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp))
}

@Composable
private fun sectionTitle(kind: SearchKind): String = stringResource(
    when (kind) {
        SearchKind.NOW -> R.string.search_now
        SearchKind.LATER -> R.string.search_later
        SearchKind.RECORDING -> R.string.search_recordings
        SearchKind.CHANNEL, SearchKind.BOUQUET -> R.string.search_stations
        SearchKind.TMDB -> R.string.search_unavailable
    },
)

@Composable
private fun detail(hit: SearchHit): String {
    val whenText = if (hit.beginEpochSec > 0) {
        Instant.ofEpochSecond(hit.beginEpochSec).atZone(ZoneId.systemDefault()).format(WhenFormat)
    } else {
        ""
    }
    return SearchRows.detail(hit, whenText, stringResource(R.string.search_bouquet))
}

private fun SearchHit.rowKey(): String = when (kind) {
    SearchKind.RECORDING -> "rec:$recordingId"
    SearchKind.BOUQUET -> "bq:$bouquetReference"
    else -> "$kind:$serviceReference:$beginEpochSec"
}
