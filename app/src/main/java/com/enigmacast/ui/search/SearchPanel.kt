package com.enigmacast.ui.search

import com.enigmacast.ui.theme.trapVertical
import com.enigmacast.ui.theme.EnigmaType
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.search.SearchHit
import com.enigmacast.domain.search.SearchKind
import com.enigmacast.domain.search.SearchQuery
import com.enigmacast.domain.search.SearchRows
import com.enigmacast.ui.theme.CompactListRow
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.EnigmaIcons
import com.enigmacast.ui.theme.LocalEdgeLeft
import com.enigmacast.ui.theme.TvTextField
import com.enigmacast.ui.theme.requestWhenReady
import com.enigmacast.ui.theme.focusRequesterIf
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
    onRetreatTo: ((FocusRequester) -> Unit)? = null,
    onXtream: (() -> Unit)? = null,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val fieldFocus = remember { FocusRequester() }
    val micFocus = remember { FocusRequester() }
    SearchProgrammeDialogs(viewModel, onPlayed, onBouquet, onPrepare)
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
    // Back from a result opened here (film, series, channel): "<row id>/<card key>" of that card.
    val returnOrigin by viewModel.returnKeys.collectAsStateWithLifecycle()
    val searchReturn = com.enigmacast.domain.xtream.LibraryReturn.searchKey(returnOrigin)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val returnFocus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }
    // OK / Done in the field: the first result gets the focus once the search has something.
    var focusFirstPending by remember { androidx.compose.runtime.mutableStateOf(false) }
    // The result list had the focus until a moment ago (a result update can take the focused row away).
    var listFocused by remember { androidx.compose.runtime.mutableStateOf(false) }
    var bodyFocused by remember { androidx.compose.runtime.mutableStateOf(false) }
    var listLostAt by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    LaunchedEffect(rowsFocusable, focusToken) {
        if (!rowsFocusable) return@LaunchedEffect
        // A pending return puts the focus on that result instead of on the field.
        if (com.enigmacast.domain.xtream.LibraryReturn.searchEntryYields(viewModel.returnKeys.value)) return@LaunchedEffect
        fieldFocus.requestWhenReady()
    }
    val nowSec = System.currentTimeMillis() / 1000
    val searchable = SearchQuery.parse(viewModel.query, nowSec).isSearchable
    val sections = viewModel.results.sections()
    val iptvResumes by viewModel.iptvResumes.collectAsStateWithLifecycle()
    val iptvHistory by viewModel.iptvHistory.collectAsStateWithLifecycle()
    val iptvFilms = remember(viewModel.iptv.films, iptvResumes, iptvHistory) {
        com.enigmacast.domain.xtream.IptvLibraryRows.withWatch(viewModel.iptv.films, iptvResumes, iptvHistory)
    }
    var openActor by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<com.enigmacast.domain.films.TmdbCastMember?>(null) }
    val iptvRows = if (onXtream == null) emptyList() else listOf(
        R.string.search_iptv_live to viewModel.iptv.live,
        R.string.search_iptv_films to iptvFilms,
        R.string.search_iptv_series to viewModel.iptv.series,
    ).filter { it.second.isNotEmpty() }
    val people = viewModel.people
    val rowIds = remember(iptvRows, people) {
        (if (people.isEmpty()) emptyList() else listOf(ACTORS_ROW to people.map { actorKey(it) })) +
            iptvRows.map { (title, cards) -> title.toString() to cards.map { it.key } }
    }
    val hasResults = viewModel.people.isNotEmpty() || iptvRows.isNotEmpty() || sections.isNotEmpty()
    LaunchedEffect(searchReturn, rowIds, rowsFocusable) {
        val key = searchReturn ?: return@LaunchedEffect
        if (!rowsFocusable) return@LaunchedEffect
        val spot = com.enigmacast.domain.search.SearchFocus.returnSpot(key, rowIds, leadingItems = 0)
        if (spot == null) {
            // Still searching: wait for the rows. Gone for good: the field (as on entering Search).
            if (viewModel.searching) return@LaunchedEffect
            viewModel.consumeReturn()
            fieldFocus.requestWhenReady()
            return@LaunchedEffect
        }
        runCatching { listState.scrollToItem(spot.first) }
        if (!returnFocus.requestWhenReady(attempts = 30)) fieldFocus.requestWhenReady()
        viewModel.consumeReturn()
        // A title chosen on an actor's page: that page again (Back there lands on the actor card).
        if (key.startsWith("$ACTORS_ROW/")) people.getOrNull(spot.second)?.let { openActor = it }
    }
    LaunchedEffect(focusFirstPending, viewModel.searching, hasResults) {
        if (!com.enigmacast.domain.search.SearchFocus.focusFirstNow(focusFirstPending, viewModel.searching, hasResults)) return@LaunchedEffect
        focusFirstPending = false
        runCatching { listState.scrollToItem(0) }
        firstFocus.requestWhenReady(attempts = 20)
    }
    // New results took the focused row away (focus fell off the page): back to the first result.
    LaunchedEffect(viewModel.results, iptvRows, viewModel.people) {
        androidx.compose.runtime.withFrameNanos { }
        kotlinx.coroutines.delay(80)
        val justLost = !bodyFocused && !listFocused && System.currentTimeMillis() - listLostAt < 600
        if (rowsFocusable && justLost && hasResults) firstFocus.requestWhenReady()
    }
    val body = modifier
        .fillMaxSize()
        // Down at the last result / Up at the field stay on the page (Left still goes to the menu).
        .trapVertical()
        .onFocusChanged {
            bodyFocused = it.hasFocus
            if (it.hasFocus && menuFocus != null) onRetreatTo?.invoke(menuFocus)
        }
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || event.key != Key.Search) return@onPreviewKeyEvent false
            startVoice()
            true
        }
    val column: @Composable () -> Unit = {
        Column(body) {
        Text(
            stringResource(R.string.nav_search),
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = EnigmaType.Strong,
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
                onDone = { focusFirstPending = true },
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
                scale = ButtonDefaults.scale(scale = 1f, focusedScale = 1.08f),
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
            viewModel.searching && viewModel.results.isEmpty && iptvRows.isEmpty() && viewModel.people.isEmpty() -> SearchSpinner()
            !viewModel.searching && viewModel.results.isEmpty && iptvRows.isEmpty() && viewModel.people.isEmpty() -> Hint(stringResource(R.string.search_empty))
            else -> {
                if (viewModel.searching) SearchSpinner()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().onFocusChanged {
                        if (listFocused && !it.hasFocus) listLostAt = System.currentTimeMillis()
                        listFocused = it.hasFocus
                    },
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    if (people.isNotEmpty()) item(key = "actors") {
                        Column {
                            Text(stringResource(R.string.search_actors), color = EnigmaColors.Accent, fontSize = 12.sp, fontWeight = EnigmaType.Strong, modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp))
                            val backActor = searchReturn?.takeIf { it.startsWith("$ACTORS_ROW/") }?.removePrefix("$ACTORS_ROW/")
                            androidx.compose.foundation.lazy.LazyRow(
                                state = androidx.compose.foundation.lazy.rememberLazyListState(
                                    initialFirstVisibleItemIndex = backActor?.let { k -> people.indexOfFirst { actorKey(it) == k }.coerceAtLeast(0) } ?: 0,
                                ),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                            ) {
                                items(people, key = { actorKey(it) }) { member ->
                                    com.enigmacast.ui.home.CastCard(
                                        member,
                                        Modifier
                                            .focusRequesterIf(backActor != null && actorKey(member) == backActor, returnFocus)
                                            .focusRequesterIf(member === people.first(), firstFocus)
                                            .focusProperties {
                                            canFocus = rowsFocusable
                                            if (member === people.first()) left = menuFocus ?: FocusRequester.Cancel
                                            if (member === people.last()) right = FocusRequester.Cancel
                                        },
                                    ) { openActor = member }
                                }
                            }
                        }
                    }
                    searchResultItems(
                        iptvRows = iptvRows,
                        sections = sections,
                        rowsFocusable = rowsFocusable,
                        menuFocus = menuFocus,
                        returnKey = searchReturn,
                        returnFocus = returnFocus,
                        firstFocus = firstFocus.takeIf { people.isEmpty() },
                        onIptv = { title, card -> onXtream?.let { viewModel.openIptv(card, title.toString(), it) } },
                        onHit = { hit ->
                            if ((hit.kind == SearchKind.NOW || hit.kind == SearchKind.LATER) && hit.event != null) viewModel.openProgramme(hit)
                            else viewModel.choose(hit, onPlayed, onBouquet, onPrepare)
                        },
                    )
                }
            }
        }
    }
    }
    openActor?.let { member ->
        com.enigmacast.ui.person.ActorInfoDialog(
            member,
            onClose = { openActor = null },
            returnOrigin = com.enigmacast.domain.xtream.LibraryReturn.searchOrigin(ACTORS_ROW, actorKey(member)),
        )
    }
    if (menuFocus != null) {
        CompositionLocalProvider(LocalEdgeLeft provides menuFocus) { column() }
    } else {
        column()
    }
}

/**
 * The result rows of a search: IPTV poster rows, then the programme / recording / channel
 * sections. Shared by [SearchPanel] and the actor page's "where to watch" list.
 */
internal fun androidx.compose.foundation.lazy.LazyListScope.searchResultItems(
    iptvRows: List<Pair<Int, List<com.enigmacast.domain.xtream.IptvLibraryCard>>>,
    sections: List<Pair<SearchKind, List<SearchHit>>>,
    rowsFocusable: Boolean,
    menuFocus: FocusRequester?,
    onIptv: (title: Int, card: com.enigmacast.domain.xtream.IptvLibraryCard) -> Unit,
    onHit: (SearchHit) -> Unit,
    /** "<row id>/<card key>" of the result to come back on (its row opens scrolled to it). */
    returnKey: String? = null,
    returnFocus: FocusRequester? = null,
    /** Put on the first result of these rows (null when an earlier row has the first result). */
    firstFocus: FocusRequester? = null,
) {
    val firstIptv = iptvRows.firstOrNull()?.second?.firstOrNull()
    val firstHit = if (firstIptv == null) sections.firstOrNull()?.second?.firstOrNull() else null
    // IPTV Live / Films / Series: sideways poster rows after the programme results.
    iptvRows.forEach { (title, cards) ->
        item(key = "iptv-$title") {
            Column {
                Text(stringResource(title), color = EnigmaColors.Accent, fontSize = 12.sp, fontWeight = EnigmaType.Strong, modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp))
                val back = returnKey?.takeIf { it.startsWith("$title/") }?.removePrefix("$title/")
                val start = back?.let { k -> cards.indexOfFirst { it.key == k }.coerceAtLeast(0) } ?: 0
                androidx.compose.foundation.lazy.LazyRow(
                    state = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = start),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    items(cards, key = { it.key }) { card ->
                        com.enigmacast.ui.dashboard.IptvCard(
                            card = card,
                            modifier = Modifier
                                .focusRequesterIf(back != null && card.key == back, returnFocus)
                                .focusRequesterIf(card === firstIptv, firstFocus)
                                .focusProperties {
                                canFocus = rowsFocusable
                                if (card === cards.first()) left = menuFocus ?: FocusRequester.Cancel
                                if (card === cards.last()) right = FocusRequester.Cancel
                            },
                            onClick = { onIptv(title, card) },
                            onOptions = {},
                        )
                    }
                }
            }
        }
    }
    sections.forEach { (kind, hits) ->
        item(key = "header-$kind") {
            Text(
                sectionTitle(kind),
                color = EnigmaColors.Accent,
                fontSize = 12.sp,
                fontWeight = EnigmaType.Strong,
                modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp),
            )
        }
        items(hits, key = { it.rowKey() }) { hit ->
            CompactListRow(
                onClick = { onHit(hit) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
                modifier = Modifier
                    .focusRequesterIf(hit === firstHit, firstFocus)
                    .focusProperties {
                        canFocus = rowsFocusable
                        left = menuFocus ?: FocusRequester.Cancel
                        if (menuFocus == null) right = FocusRequester.Cancel
                    },
            ) {
                Column {
                    Text(SearchRows.headline(hit), color = EnigmaColors.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val subtitle = searchHitDetail(hit)
                    if (subtitle.isNotBlank()) {
                        Text(subtitle, color = EnigmaColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SearchSpinner() {
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
internal fun sectionTitle(kind: SearchKind): String = stringResource(
    when (kind) {
        SearchKind.NOW -> R.string.search_now
        SearchKind.LATER -> R.string.search_later
        SearchKind.RECORDING -> R.string.search_recordings
        SearchKind.CHANNEL, SearchKind.BOUQUET -> R.string.search_stations
        SearchKind.TMDB -> R.string.search_unavailable
    },
)

@Composable
internal fun searchHitDetail(hit: SearchHit): String {
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

/** OK on a found programme: Watch now (only while it airs), AutoSwitch, Record (TV or box), Cancel. */
@Composable
internal fun SearchProgrammeDialogs(viewModel: SearchViewModel, onPlayed: () -> Unit, onBouquet: (BouquetEntry) -> Unit, onPrepare: () -> Unit) {
    val autoKeys by viewModel.autoSwitchKeys.collectAsStateWithLifecycle()
    viewModel.programme?.let { hit ->
        val event = hit.event ?: return@let
        val nowSec = System.currentTimeMillis() / 1000
        val airing = event.isAiring(nowSec)
        val autoSet = event.programmeKey in autoKeys
        val watch = stringResource(R.string.search_watch_now)
        val auto = stringResource(if (autoSet) R.string.autoswitch_cancel_action else R.string.search_autoswitch)
        val record = stringResource(R.string.home_record)
        val choices = buildList {
            if (airing) add(watch)
            if (event.beginEpochSec > nowSec) add(auto)
            if (event.endEpochSec > nowSec) add(record)
            add(stringResource(R.string.cancel))
        }
        com.enigmacast.ui.theme.TvChoiceDialog(
            title = hit.title,
            message = listOf(hit.channelName, SearchRows.headline(hit).takeIf { it != hit.title }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
            choices = choices,
            onChoose = { i ->
                when (choices[i]) {
                    watch -> { viewModel.dismissProgramme(); viewModel.choose(hit, onPlayed, onBouquet, onPrepare) }
                    auto -> viewModel.toggleAutoSwitch()
                    record -> viewModel.chooseRecord()
                    else -> viewModel.dismissProgramme()
                }
            },
            onDismiss = viewModel::dismissProgramme,
        )
    }
    viewModel.recordAsk?.let { ask ->
        com.enigmacast.ui.recording.RecordConfirmDialog(ask, onConfirm = viewModel::confirmRecord, onDismiss = viewModel::dismissRecord)
    }
}

/** Row id and card key of the Actors row in a Search origin ("search/actors/p<id>"). */
private const val ACTORS_ROW = "actors"
private fun actorKey(member: com.enigmacast.domain.films.TmdbCastMember): String = "p${member.id}"
