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
fun ActorInfoDialog(member: TmdbCastMember, onClose: () -> Unit) {
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
                WhereToWatchDialog(credit, viewModel, onClose = {
                    chosen = null
                    returnTo = index
                })
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
 * Where a title from the filmography can be watched, like Search: IPTV films and series, the
 * Torrent page (when it is on) and satellite guide broadcasts with Watch / AutoSwitch / Record.
 */
@Composable
private fun WhereToWatchDialog(credit: TmdbCredit, actor: ActorViewModel, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        NavigationSoundsForWindow()
        val search: SearchViewModel = hiltViewModel(key = "actor-where-to-watch")
        val nav = LocalTitleNavigation.current
        val onPlayed = nav?.onPlayed ?: {}
        LaunchedEffect(credit.key) { search.onQuery(credit.title, immediate = true) }
        SearchProgrammeDialogs(search, onPlayed, {}, {})
        val firstFocus = remember { FocusRequester() }
        LaunchedEffect(credit.key) { firstFocus.requestWhenReady() }
        val torrents = remember(credit.key) { actor.torrentsAllowed() } && nav != null
        val iptvResumes by search.iptvResumes.collectAsStateWithLifecycle()
        val iptvHistory by search.iptvHistory.collectAsStateWithLifecycle()
        val iptvFilms = remember(search.iptv.films, iptvResumes, iptvHistory) {
            com.enigmacast.domain.xtream.IptvLibraryRows.withWatch(search.iptv.films, iptvResumes, iptvHistory)
        }
        val iptvRows = if (nav == null) emptyList() else listOf(
            R.string.search_iptv_films to iptvFilms,
            R.string.search_iptv_series to search.iptv.series,
        ).filter { it.second.isNotEmpty() }
        val sections = search.results.sections().filter { it.first != SearchKind.CHANNEL }
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
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.where_to_watch), color = EnigmaColors.Accent, fontSize = 13.sp, fontWeight = EnigmaType.Strong)
                Text(
                    listOfNotNull(credit.title, credit.year?.let { "($it)" }).joinToString(" "),
                    color = EnigmaColors.Text, fontSize = 24.sp, fontWeight = EnigmaType.Strong, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (credit.character.isNotBlank()) {
                    Text(stringResource(R.string.actor_as, credit.character), color = EnigmaColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    if (torrents) {
                        EnigmaGlassButton(
                            stringResource(R.string.where_to_watch_torrent),
                            { actor.openTorrent(credit) { nav?.onTorrent?.invoke() } },
                            Modifier.focusRequester(firstFocus).focusProperties { left = FocusRequester.Cancel },
                        )
                        EnigmaGlassButton(stringResource(R.string.home_close), onClose)
                    } else {
                        EnigmaGlassButton(stringResource(R.string.home_close), onClose, Modifier.focusRequester(firstFocus).focusProperties { left = FocusRequester.Cancel })
                    }
                }
                val empty = sections.isEmpty() && iptvRows.isEmpty()
                when {
                    search.searching && empty -> SearchSpinner()
                    empty -> Text(stringResource(R.string.where_to_watch_none), color = EnigmaColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
                    else -> LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        searchResultItems(
                            iptvRows = iptvRows,
                            sections = sections,
                            rowsFocusable = true,
                            menuFocus = null,
                            onIptv = { card -> nav?.let { search.openIptv(card, it.onXtream) } },
                            onHit = { hit ->
                                if ((hit.kind == SearchKind.NOW || hit.kind == SearchKind.LATER) && hit.event != null) search.openProgramme(hit)
                                else search.choose(hit, onPlayed, {}, {})
                            },
                        )
                    }
                }
            }
        }
    }
}
