package com.enigmacast.ui.person

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.CircularProgressIndicator
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.enigmacast.R
import com.enigmacast.data.tmdb.TmdbJson
import com.enigmacast.domain.films.PersonCredits
import com.enigmacast.domain.films.TmdbCastMember
import com.enigmacast.domain.films.TmdbCredit
import com.enigmacast.domain.films.TmdbPerson
import com.enigmacast.domain.search.SearchKind
import com.enigmacast.ui.components.EnigmaGlassButton
import com.enigmacast.ui.home.PLOT_MORE_CHARS
import com.enigmacast.ui.home.PlotDialog
import com.enigmacast.ui.search.SearchProgrammeDialogs
import com.enigmacast.ui.search.SearchSpinner
import com.enigmacast.ui.search.SearchViewModel
import com.enigmacast.ui.search.searchResultItems
import com.enigmacast.ui.search.sections
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.EnigmaType
import com.enigmacast.ui.theme.NavigationSoundsForWindow
import com.enigmacast.ui.theme.requestWhenReady
import com.enigmacast.ui.theme.skinCanvas
import com.enigmacast.ui.theme.skinShape
import com.enigmacast.ui.theme.trapVertical

/**
 * An actor's page over the film or series info page: photo, birth and death, biography and the
 * filmography as posters. OK on a poster shows where that title can be watched; Back closes.
 */
@Composable
fun ActorInfoDialog(
    member: TmdbCastMember,
    onClose: () -> Unit,
    /** Opened on the Search page: a title chosen here comes back to Search (on this actor) with Back. */
    returnOrigin: String? = null,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        NavigationSoundsForWindow()
        val viewModel: ActorViewModel = hiltViewModel(key = "actor-page")
        LaunchedEffect(member) { viewModel.load(member) }
        val closeFocus = remember { FocusRequester() }
        var showBio by remember { mutableStateOf(false) }
        var chosen by remember { mutableStateOf<Int?>(null) }
        var returnTo by remember { mutableStateOf<Int?>(null) }
        val state = viewModel.state
        val person = (state as? ActorState.Ready)?.person
        val credits = person?.credits.orEmpty()
        val posterFocus = remember(credits) { List(credits.size) { FocusRequester() } }
        // Back from a title chosen on "where to watch": its poster chosen again, so that list reopens.
        LaunchedEffect(credits) {
            if (credits.isEmpty()) return@LaunchedEffect
            val key = TitleTrails.shared.takeCredit(com.enigmacast.domain.person.TitleTrail.actorKey(member.name, member.character)) ?: return@LaunchedEffect
            credits.indexOfFirst { it.key == key }.takeIf { it >= 0 }?.let { chosen = it }
        }
        LaunchedEffect(person?.id) {
            // The films first: that is what the page is for; Close when there are none.
            if (posterFocus.isNotEmpty()) posterFocus[0].requestWhenReady() else closeFocus.requestWhenReady()
        }
        Box(Modifier.fillMaxSize().skinCanvas()) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to EnigmaColors.Raised.copy(alpha = 0.35f), 1f to EnigmaColors.Canvas)))
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = EnigmaColors.SafeHorizontal, vertical = EnigmaColors.SafeVertical),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Box(
                        Modifier
                            .width(150.dp)
                            .height(225.dp)
                            .clip(skinShape(12.dp))
                            .background(EnigmaColors.Card)
                            .border(1.dp, EnigmaColors.GlassEdge, skinShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        val photo = person?.profilePath ?: member.profilePath
                        if (photo != null) {
                            AsyncImage(
                                model = TmdbJson.image(photo, "h632"),
                                contentDescription = member.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            com.enigmacast.ui.components.DefaultArtwork(member.name, Modifier.fillMaxSize())
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(person?.name?.ifBlank { null } ?: member.name, color = EnigmaColors.Text, fontSize = 28.sp, fontWeight = EnigmaType.Strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (person != null) {
                            val facts = personFacts(person)
                            if (facts.isNotBlank()) Text(facts, color = EnigmaColors.Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                person.biography.ifBlank { stringResource(R.string.actor_no_bio) },
                                color = EnigmaColors.Muted,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        when (state) {
                            ActorState.Loading -> CircularProgressIndicator(color = EnigmaColors.Accent, strokeWidth = 3.dp, modifier = Modifier.size(26.dp))
                            ActorState.Failed -> Text(stringResource(R.string.actor_failed), color = EnigmaColors.Muted, fontSize = 14.sp)
                            is ActorState.Ready -> Unit
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                            if ((person?.biography?.length ?: 0) > PLOT_MORE_CHARS) {
                                EnigmaGlassButton(stringResource(R.string.home_more), { showBio = true })
                            }
                            EnigmaGlassButton(stringResource(R.string.home_close), onClose, Modifier.focusRequester(closeFocus))
                        }
                    }
                }
                if (credits.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.actor_filmography), color = EnigmaColors.Text, fontSize = 15.sp, fontWeight = EnigmaType.Strong)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 2.dp, horizontal = 2.dp)) {
                            itemsIndexed(credits, key = { _, it -> it.key }) { index, credit ->
                                CreditCard(
                                    credit,
                                    Modifier.focusRequester(posterFocus[index]).focusProperties {
                                        if (index == 0) left = FocusRequester.Cancel
                                        if (index == credits.lastIndex) right = FocusRequester.Cancel
                                        down = FocusRequester.Cancel
                                    },
                                ) { chosen = index }
                            }
                        }
                    }
                }
            }
        }
        if (showBio && person != null) PlotDialog(person.name, person.biography, onClose = { showBio = false })
        chosen?.let { index ->
            credits.getOrNull(index)?.let { credit ->
                WhereToWatchDialog(credit, member, viewModel, onClose = {
                    chosen = null
                    returnTo = index
                }, onJumped = {
                    // A title opened elsewhere: this page and "where to watch" make way for it.
                    chosen = null
                    onClose()
                }, returnOrigin = returnOrigin)
            }
        }
        LaunchedEffect(returnTo) {
            val index = returnTo ?: return@LaunchedEffect
            returnTo = null
            kotlinx.coroutines.delay(60)
            runCatching { posterFocus[index].requestFocus() }
        }
    }
}

@Composable
private fun personFacts(person: TmdbPerson): String {
    val parts = mutableListOf<String>()
    val today = java.time.LocalDate.now().toString()
    person.birthday?.let { born ->
        val age = if (person.deathday == null) PersonCredits.age(born, today) else null
        val text = stringResource(R.string.actor_born, longDate(born)) + (person.birthPlace?.let { ", $it" } ?: "")
        parts += if (age != null) "$text (" + stringResource(R.string.actor_age, age) + ")" else text
    }
    person.deathday?.let { died ->
        val age = PersonCredits.age(person.birthday, died)
        val text = stringResource(R.string.actor_died, longDate(died))
        parts += if (age != null) "$text (" + stringResource(R.string.actor_age, age) + ")" else text
    }
    return parts.joinToString("  ·  ")
}

private fun longDate(iso: String): String = runCatching {
    java.time.LocalDate.parse(iso).format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.LONG))
}.getOrDefault(iso)

@Composable
private fun CreditCard(credit: TmdbCredit, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(
        modifier.width(92.dp).clickable(interactionSource = interaction, indication = null, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            Modifier
                .width(92.dp)
                .height(138.dp)
                .clip(skinShape(8.dp))
                .background(EnigmaColors.Card)
                .border(if (focused) 2.dp else 1.dp, if (focused) EnigmaColors.Cyan else EnigmaColors.CardBorder, skinShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (credit.posterPath != null) {
                AsyncImage(
                    model = TmdbJson.image(credit.posterPath, "w185"),
                    contentDescription = credit.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                com.enigmacast.ui.components.DefaultArtwork(credit.title, Modifier.fillMaxSize())
            }
        }
        Text(credit.title, color = if (focused) EnigmaColors.Text else EnigmaColors.Muted, fontSize = 11.sp, fontWeight = EnigmaType.Strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val kind = stringResource(if (credit.isSeries) R.string.actor_kind_series else R.string.actor_kind_film)
        Text(listOfNotNull(credit.year?.toString(), kind).joinToString(" · "), color = EnigmaColors.Subtle, fontSize = 10.sp, maxLines = 1)
    }
}

/**
 * Where a title from the filmography can be watched: one row per source, each with its own
 * header and its own searching / nothing found state, using the searches Search uses:
 * IPTV films and series, the satellite TV guide (Watch / AutoSwitch / Record), the IPTV TV
 * guide and, when the Torrent page is on, the torrents found for it.
 */
@Composable
private fun WhereToWatchDialog(credit: TmdbCredit, member: TmdbCastMember, actor: ActorViewModel, onClose: () -> Unit, onJumped: () -> Unit, returnOrigin: String? = null) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        NavigationSoundsForWindow()
        val search: SearchViewModel = hiltViewModel(key = "actor-where-to-watch")
        val rows: WhereToWatchViewModel = hiltViewModel(key = "actor-where-to-watch-rows")
        val nav = LocalTitleNavigation.current
        val origin = LocalTrailOrigin.current
        val onPlayed = nav?.onPlayed ?: {}
        val torrentsOn = remember(credit.key) { actor.torrentsAllowed() } && nav != null
        LaunchedEffect(credit.key) { rows.search(credit, torrentsOn) }
        SearchProgrammeDialogs(search, onPlayed, {}, {})
        val closeFocus = remember { FocusRequester() }
        LaunchedEffect(credit.key) { closeFocus.requestWhenReady() }
        val iptvResumes by search.iptvResumes.collectAsStateWithLifecycle()
        val iptvHistory by search.iptvHistory.collectAsStateWithLifecycle()
        val vod = rows.vod
        val vodCards = remember(vod, iptvResumes, iptvHistory) {
            val found = vod.found
            val films = com.enigmacast.domain.xtream.IptvLibraryRows.withWatch(found.filter { it.kind == "m" }, iptvResumes, iptvHistory)
            films + found.filter { it.kind != "m" }
        }
        val actorKey = com.enigmacast.domain.person.TitleTrail.actorKey(member.name, member.character)
        // Opening a title's info page: remember the way back here first.
        fun jumpTo(targetKey: String) {
            origin?.let { TitleTrails.shared.push(com.enigmacast.domain.person.TrailStep(it, actorKey, credit.key, targetKey)) }
        }
        Box(Modifier.fillMaxSize().skinCanvas()) {
            if (credit.backdropPath != null) {
                AsyncImage(
                    model = TmdbJson.image(credit.backdropPath, "w1280"),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to EnigmaColors.Canvas, 0.6f to EnigmaColors.Canvas.copy(alpha = 0.9f), 1f to EnigmaColors.Canvas.copy(alpha = 0.5f))))
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to EnigmaColors.Canvas)))
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = EnigmaColors.SafeHorizontal, vertical = EnigmaColors.SafeVertical)
                    .trapVertical(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.where_to_watch), color = EnigmaColors.Accent, fontSize = 13.sp, fontWeight = EnigmaType.Strong)
                        Text(
                            listOfNotNull(credit.title, credit.year?.let { "($it)" }).joinToString(" "),
                            color = EnigmaColors.Text, fontSize = 22.sp, fontWeight = EnigmaType.Strong, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (credit.character.isNotBlank()) {
                            Text(stringResource(R.string.actor_as, credit.character), color = EnigmaColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    EnigmaGlassButton(
                        stringResource(R.string.home_close),
                        onClose,
                        Modifier.focusRequester(closeFocus).focusProperties { left = FocusRequester.Cancel; right = FocusRequester.Cancel },
                    )
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    if (nav != null) sourceRow(R.string.wtw_row_iptv_vod, vod, "vod") {
                        item(key = "vod-row") {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                            ) {
                                items(vodCards, key = { it.key }) { card ->
                                    com.enigmacast.ui.dashboard.IptvCard(
                                        card = card,
                                        modifier = Modifier.focusProperties {
                                            if (card === vodCards.first()) left = FocusRequester.Cancel
                                            if (card === vodCards.last()) right = FocusRequester.Cancel
                                        },
                                        onClick = {
                                            jumpTo(com.enigmacast.domain.person.TitleTrail.iptvKey(card.open))
                                            search.openIptvFrom(card, returnOrigin) { nav.onXtream(); onJumped() }
                                        },
                                        onOptions = {},
                                    )
                                }
                            }
                        }
                    }
                    sourceRow(R.string.wtw_row_sat_epg, rows.satellite, "sat") {
                        items(rows.satellite.found, key = { "sat:${it.serviceReference}:${it.beginEpochSec}" }) { hit ->
                            HitRow(hit) { openHit(search, hit, onPlayed) }
                        }
                    }
                    sourceRow(R.string.wtw_row_iptv_epg, rows.iptvTv, "iptvtv") {
                        items(rows.iptvTv.found, key = { item ->
                            when (item) {
                                is IptvTvItem.Guide -> "g:${item.hit.serviceReference}:${item.hit.beginEpochSec}"
                                is IptvTvItem.Channel -> "c:${item.hit.source}:${item.hit.streamId}:${item.hit.programme.startSec}"
                            }
                        }) { item ->
                            when (item) {
                                is IptvTvItem.Guide -> HitRow(item.hit) { openHit(search, item.hit, onPlayed) }
                                is IptvTvItem.Channel -> ListRow(
                                    item.hit.programme.title,
                                    listOf(item.channel.name, guideWhen(item.hit.programme.startSec)).filter { it.isNotBlank() }.joinToString("  ·  "),
                                ) { nav?.let { n -> search.openIptvFrom(item.card, returnOrigin) { n.onXtream(); onJumped() } } }
                            }
                        }
                    }
                    if (rows.recorded.isNotEmpty()) {
                        item(key = "rec-header") { RowHeader(stringResource(R.string.search_recordings)) }
                        items(rows.recorded, key = { "rec:${it.recordingId}" }) { hit ->
                            HitRow(hit) { search.choose(hit, onPlayed, {}, {}) }
                        }
                    }
                    if (torrentsOn) sourceRow(R.string.wtw_row_torrents, rows.torrents, "torrent") {
                        items(rows.torrents.found, key = { "torrent:${it.credit.key}" }) { find ->
                            ListRow(
                                listOfNotNull(find.credit.title, find.credit.year?.let { "($it)" }).joinToString(" "),
                                pluralStringResource(R.plurals.wtw_torrent_sources, find.sources, find.sources),
                            ) {
                                actor.openTorrent(find.credit, beforeOpen = { jumpTo(com.enigmacast.domain.person.TitleTrail.tmdbKey(it)) }) {
                                    nav?.onTorrent?.invoke()
                                    onJumped()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A source's header, then its spinner, "nothing found" or its [content]; nothing when the source is off. */
private fun <T> androidx.compose.foundation.lazy.LazyListScope.sourceRow(
    title: Int,
    state: com.enigmacast.domain.person.SourceState<T>,
    key: String,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    if (state is com.enigmacast.domain.person.SourceState.Off) return
    item(key = "$key-header") { RowHeader(stringResource(title)) }
    when {
        state is com.enigmacast.domain.person.SourceState.Searching -> item(key = "$key-busy") { SearchSpinner() }
        state.found.isEmpty() -> item(key = "$key-none") {
            Text(stringResource(R.string.wtw_nothing_found), color = EnigmaColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
        }
        else -> content()
    }
}

@Composable
private fun RowHeader(text: String) {
    Text(text, color = EnigmaColors.Accent, fontSize = 12.sp, fontWeight = EnigmaType.Strong, modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp))
}

@Composable
private fun HitRow(hit: com.enigmacast.domain.search.SearchHit, onClick: () -> Unit) {
    ListRow(com.enigmacast.domain.search.SearchRows.headline(hit), com.enigmacast.ui.search.searchHitDetail(hit), onClick)
}

/** One focusable result line; left and right stay in the list. */
@Composable
private fun ListRow(title: String, subtitle: String, onClick: () -> Unit) {
    com.enigmacast.ui.theme.CompactListRow(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
        modifier = Modifier.focusProperties { left = FocusRequester.Cancel; right = FocusRequester.Cancel },
    ) {
        Column {
            Text(title, color = EnigmaColors.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, color = EnigmaColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A guide programme: Watch / AutoSwitch / Record while it is on or still to come, like Search. */
private fun openHit(search: SearchViewModel, hit: com.enigmacast.domain.search.SearchHit, onPlayed: () -> Unit) {
    if ((hit.kind == SearchKind.NOW || hit.kind == SearchKind.LATER) && hit.event != null) search.openProgramme(hit)
    else search.choose(hit, onPlayed, {}, {})
}

private val GuideWhen = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM  HH:mm")

private fun guideWhen(startSec: Long): String =
    java.time.Instant.ofEpochSecond(startSec).atZone(java.time.ZoneId.systemDefault()).format(GuideWhen)
