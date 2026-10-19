package com.enigmacast.ui.soccer

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import com.enigmacast.R
import com.enigmacast.domain.soccer.SportRegions
import com.enigmacast.ui.animation.LocalMotionSpec
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.EnigmaType
import com.enigmacast.ui.theme.focusRequesterIf
import com.enigmacast.ui.theme.skinShape

/**
 * The first page of Sport: a hero for the focused region (drawn backdrop in its colours, name,
 * three lines of text, the main competition's emblem) and a row of region cards. OK opens the
 * Soccer page for that region. Up from the cards reaches Refresh; Left from the first card the menu.
 */
@Composable
fun SportLanding(
    cards: List<SportRegions.Card>,
    focusedIndex: Int,
    onFocusIndex: (Int) -> Unit,
    contentFocus: FocusRequester,
    menuFocus: FocusRequester,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpen: (SportRegions.Card) -> Unit,
) {
    if (cards.isEmpty()) return
    val index = focusedIndex.coerceIn(0, cards.lastIndex)
    val card = cards[index]
    val cardFocus = remember(cards.size) { List(cards.size) { FocusRequester() } }
    val refreshFocus = remember { FocusRequester() }
    // Starts with the focused card in view; afterwards the row keeps the focused card visible itself.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (index - 1).coerceAtLeast(0))

    Box(Modifier.fillMaxSize()) {
        Crossfade(targetState = card.id, animationSpec = tween(if (LocalMotionSpec.current.animate) 450 else 0), label = "sportHero") { id ->
            SportBackdrop(cards.firstOrNull { it.id == id }?.look ?: card.look, Modifier.fillMaxSize())
        }
        // Text side and card row stay readable on every backdrop.
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to EnigmaColors.Canvas.copy(alpha = 0.92f), 0.55f to EnigmaColors.Canvas.copy(alpha = 0.35f), 1f to Color.Transparent),
            ),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to EnigmaColors.Canvas.copy(alpha = 0.9f))))

        Column(
            Modifier.align(Alignment.TopStart).padding(start = 40.dp, top = 40.dp, end = 300.dp).widthIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(countryName(card.id), color = EnigmaColors.Text, fontSize = 36.sp, fontWeight = EnigmaType.Strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                regionDescription(card.id) + " " + stringResource(R.string.sport_competitions, card.competitionNames.joinToString(", ")),
                color = EnigmaColors.Text.copy(alpha = 0.86f),
                fontSize = 17.sp,
                lineHeight = 25.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Column(
            Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 28.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            RefreshPill(
                refreshing = refreshing,
                onClick = onRefresh,
                modifier = Modifier
                    .focusRequester(refreshFocus)
                    .focusProperties {
                        up = FocusRequester.Cancel
                        right = FocusRequester.Cancel
                        left = menuFocus
                        down = cardFocus[index]
                    },
            )
            Crossfade(targetState = card.id, label = "sportLogo") { id ->
                val shown = cards.firstOrNull { it.id == id } ?: card
                RegionLogo(shown, 150.dp)
            }
        }

        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.padding(start = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(3.dp).height(18.dp).background(EnigmaColors.Focus, skinShape(2.dp)))
                Text(stringResource(R.string.sport_section_football), color = EnigmaColors.Text, fontSize = 16.sp, fontWeight = EnigmaType.Strong)
            }
            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = 40.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(cards, key = { _, c -> c.id }) { i, c ->
                    RegionCard(
                        card = c,
                        modifier = Modifier
                            .focusRequester(cardFocus[i])
                            .focusRequesterIf(i == index, contentFocus)
                            .focusProperties {
                                if (i == 0) left = menuFocus
                                if (i == cards.lastIndex) right = FocusRequester.Cancel
                                up = refreshFocus
                                down = FocusRequester.Cancel
                            }
                            .onFocusChanged { if (it.isFocused) onFocusIndex(i) },
                        onClick = { onOpen(c) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RefreshPill(refreshing: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val focus = rememberTvFocus()
    val shape = CircleShape
    Row(
        modifier
            .clip(shape)
            .background(if (focus.focused) EnigmaColors.Focus else EnigmaColors.Canvas.copy(alpha = 0.55f))
            .border(1.dp, if (focus.focused) EnigmaColors.Focus else EnigmaColors.Text.copy(alpha = 0.22f), shape)
            .tvClick(focus, onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val color = if (focus.focused) EnigmaColors.OnFocus else EnigmaColors.Text
        if (refreshing) {
            com.enigmacast.ui.components.EnigmaLoadingIndicator(Modifier.size(16.dp))
        } else {
            Icon(Icons.Filled.Refresh, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        }
        Text(stringResource(R.string.sport_refresh), color = color, fontSize = 14.sp, fontWeight = EnigmaType.Strong)
    }
}

@Composable
private fun RegionCard(card: SportRegions.Card, modifier: Modifier, onClick: () -> Unit) {
    val focus = rememberTvFocus()
    val spec = LocalMotionSpec.current
    val scale by animateFloatAsState(if (focus.focused && spec.animate) 1.05f else 1f, tween(spec.cardMs), label = "regionCard")
    val shape = skinShape(16.dp)
    Column(
        modifier
            .width(180.dp)
            .height(150.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(EnigmaColors.Text.copy(alpha = if (focus.focused) 0.18f else 0.08f))
            .border(if (focus.focused) 2.5.dp else 1.dp, if (focus.focused) EnigmaColors.Focus else EnigmaColors.Text.copy(alpha = 0.16f), shape)
            .tvClick(focus, onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { RegionLogo(card, 70.dp) }
        Text(
            countryName(card.id),
            color = EnigmaColors.Text,
            fontSize = 14.sp,
            fontWeight = if (focus.focused) EnigmaType.Strong else FontWeight.Normal,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The main competition's emblem (football-data.org, loaded by Coil off the main thread); the region's colours and code until then. */
@Composable
private fun RegionLogo(card: SportRegions.Card, size: Dp) {
    val context = LocalContext.current
    var failed by remember(card.emblem) { mutableStateOf(false) }
    if (card.emblem.isBlank() || failed) {
        Box(
            Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(Color(card.look.primary), Color(card.look.secondary)))),
            contentAlignment = Alignment.Center,
        ) {
            Text(card.logoCompetition, color = Color.White, fontSize = (size.value / 3.6f).sp, fontWeight = FontWeight.Bold)
        }
        return
    }
    val request = remember(card.emblem) {
        ImageRequest.Builder(context).data(card.emblem).crossfade(true).apply {
            if (card.emblem.endsWith(".svg", ignoreCase = true)) decoderFactory(SvgDecoder.Factory())
        }.build()
    }
    AsyncImage(model = request, contentDescription = card.competitionNames.firstOrNull(), contentScale = ContentScale.Fit, onError = { failed = true }, modifier = Modifier.size(size))
}

/**
 * Drawn, not a photo: a dark field with two soft glows in the region's colours and faint pitch
 * markings in perspective. No image assets, nothing to license, nothing to decode.
 */
@Composable
fun SportBackdrop(look: SportRegions.Look, modifier: Modifier = Modifier) {
    val base = EnigmaColors.Canvas
    val primary = Color(look.primary)
    val secondary = Color(look.secondary)
    Canvas(modifier.background(base)) {
        val w = size.width
        val h = size.height
        drawRect(Brush.radialGradient(listOf(primary.copy(alpha = 0.55f), Color.Transparent), center = Offset(w * 0.72f, h * 0.18f), radius = w * 0.48f))
        drawRect(Brush.radialGradient(listOf(secondary.copy(alpha = 0.38f), Color.Transparent), center = Offset(w * 0.95f, h * 0.75f), radius = w * 0.40f))
        drawRect(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xFF0B3A22).copy(alpha = 0.55f)))
        // Pitch lines: halfway line and centre circle seen from the stand, very faint.
        val line = Color.White.copy(alpha = 0.07f)
        val stroke = Stroke(width = h * 0.004f)
        val horizon = h * 0.58f
        drawLine(line, Offset(w * 0.30f, horizon), Offset(w * 1.05f, horizon), strokeWidth = stroke.width)
        drawLine(line, Offset(w * 0.68f, horizon), Offset(w * 0.60f, h), strokeWidth = stroke.width)
        drawOval(line, topLeft = Offset(w * 0.50f, horizon + h * 0.08f), size = androidx.compose.ui.geometry.Size(w * 0.32f, h * 0.16f), style = stroke)
        drawLine(line, Offset(w * 0.20f, h * 0.92f), Offset(w * 1.10f, h * 0.80f), strokeWidth = stroke.width)
    }
}

@Composable
private fun regionDescription(id: String): String = stringResource(
    when (id) {
        "NL" -> R.string.sport_desc_nl
        "ENG" -> R.string.sport_desc_eng
        "DE" -> R.string.sport_desc_de
        "ES" -> R.string.sport_desc_es
        "IT" -> R.string.sport_desc_it
        "FR" -> R.string.sport_desc_fr
        "PT" -> R.string.sport_desc_pt
        "BR" -> R.string.sport_desc_br
        "EU" -> R.string.sport_desc_eu
        else -> R.string.sport_desc_world
    },
)
