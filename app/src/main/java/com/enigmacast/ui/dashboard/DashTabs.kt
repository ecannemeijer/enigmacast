package com.enigmacast.ui.dashboard

import com.enigmacast.ui.theme.requestWhenReady
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.ui.theme.CompactListRow
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.EnigmaType
import com.enigmacast.ui.theme.skinShape

/** Main-menu groups that open a flyout. TV's rows are dashboard pages; IPTV's are playlist sections. */
internal enum class DashGroup { TV, IPTV }

internal object DashGroups {
    private val TV = listOf(DashSection.Stream, DashSection.Guide, DashSection.Movies, DashSection.Series, DashSection.Radio)

    fun groupOf(section: DashSection): DashGroup? = when (section) {
        DashSection.IptvMovies, DashSection.IptvSeries, DashSection.IptvGuide -> DashGroup.IPTV
        in TV -> DashGroup.TV
        else -> null
    }

    fun tabs(group: DashGroup): List<DashSection> = if (group == DashGroup.TV) TV else emptyList()

    /**
     * The page OK on a group opens: the last-used tab, else the first open one. Live TV is a
     * tab that opens the full player, so the TV group opens on a page (the guide first).
     */
    fun entry(group: DashGroup, last: DashSection?, open: (DashSection) -> Boolean): DashSection {
        val pages = tabs(group).filter { it != DashSection.Stream }
        val order = listOfNotNull(last?.takeIf { it in pages }) + pages
        return order.firstOrNull(open) ?: pages.last()
    }

    /** The entry shown under the item: the open page of the group, else the last one used. */
    fun current(group: DashGroup, section: DashSection, last: DashSection?): DashSection? = when {
        groupOf(section) == group && section != DashSection.Stream -> section
        last != null && groupOf(last) == group -> last
        else -> null
    }

    /** Opening a group always starts on its first entry. */
    fun <T> flyoutStart(entries: List<T>, active: T?): Int = if (entries.isEmpty() || active == null) 0 else 0

    @StringRes
    fun label(section: DashSection): Int = when (section) {
        DashSection.Stream -> R.string.nav_live
        DashSection.Guide -> R.string.nav_guide
        DashSection.Movies -> R.string.nav_movies
        DashSection.Series -> R.string.nav_series
        DashSection.Radio -> R.string.nav_radio
        DashSection.MyItems -> R.string.nav_my_items
        DashSection.Search -> R.string.nav_search
        else -> R.string.nav_home
    }
}

internal fun xtreamSectionLabel(section: com.enigmacast.domain.xtream.XtreamSection): Int = when (section) {
    com.enigmacast.domain.xtream.XtreamSection.LIVE -> R.string.xtream_live
    com.enigmacast.domain.xtream.XtreamSection.MOVIES -> R.string.xtream_movies
    com.enigmacast.domain.xtream.XtreamSection.SERIES -> R.string.xtream_series
}

/** The last-used tab per group, kept across app starts (only the tab name). */
internal class DashTabMemory(context: Context) {
    private val prefs = context.getSharedPreferences("dashboard_tabs", Context.MODE_PRIVATE)

    fun last(group: DashGroup): DashSection? =
        prefs.getString(group.name, null)?.let { name -> DashSection.entries.firstOrNull { it.name == name } }

    fun remember(group: DashGroup, section: DashSection) {
        if (section == DashSection.Stream) return
        if (last(group) != section) prefs.edit().putString(group.name, section.name).apply()
    }
}

internal data class DashFlyoutEntry<T>(val id: T, val label: String, val locked: Boolean)

/**
 * Submenu rows inside the drawer, directly under TV or IPTV.
 * The label starts where that item's text starts. Up/Down move, OK opens, Left closes.
 * Focus starts on the first entry.
 */
@Composable
internal fun <T> DashFlyout(
    entries: List<DashFlyoutEntry<T>>,
    active: T?,
    entryFocus: FocusRequester,
    upTo: FocusRequester,
    downTo: FocusRequester,
    onClose: () -> Unit,
    onChoose: (DashFlyoutEntry<T>) -> Unit,
) {
    val cursor = com.enigmacast.ui.components.LocalNavCursor.current
    val focus = remember(entries.size) { List(entries.size) { FocusRequester() } }
    LaunchedEffect(Unit) { entryFocus.requestWhenReady() }
    entries.forEachIndexed { index, entry ->
        val token = remember { Any() }
        val current = entry.id == active
        CompactListRow(
            onClick = { onChoose(entry) },
            selected = current && cursor == null,
            drawFocus = cursor == null,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 34.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
            onPlaced = { coords ->
                val rail = cursor ?: return@CompactListRow
                rail.owner = token
                rail.topPx = coords.positionInRoot().y - rail.originY
                rail.heightPx = coords.size.height.toFloat()
                rail.visible = true
            },
            modifier = Modifier
                .focusRequester(focus[index])
                .then(if (index == 0) Modifier.focusRequester(entryFocus) else Modifier)
                .onFocusChanged { if (!it.isFocused && cursor?.owner == token) cursor.visible = false }
                .focusProperties {
                    right = FocusRequester.Cancel
                    if (index == 0) up = upTo
                    if (index == entries.lastIndex) down = downTo
                }
                .onPreviewKeyEvent { e ->
                    if (e.key == Key.DirectionLeft && e.type == KeyEventType.KeyDown) {
                        onClose()
                        true
                    } else {
                        false
                    }
                },
        ) { focused ->
            Text(
                entry.label,
                color = when {
                    focused -> EnigmaColors.OnFocus
                    current -> EnigmaColors.Accent
                    else -> EnigmaColors.Text
                },
                fontSize = 12.sp,
                fontWeight = if (current) EnigmaType.Strong else null,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (entry.locked) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = if (focused) EnigmaColors.OnFocus else EnigmaColors.Muted,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}
