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
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.EnigmaType
import com.enigmacast.ui.theme.skinShape

/** Main-menu groups whose pages are tabs. */
internal enum class DashGroup { TV }

internal object DashGroups {
    private val TV = listOf(DashSection.Stream, DashSection.Guide, DashSection.Movies, DashSection.Series, DashSection.Radio)

    fun groupOf(section: DashSection): DashGroup? = when (section) {
        in TV -> DashGroup.TV
        else -> null
    }

    fun tabs(@Suppress("UNUSED_PARAMETER") group: DashGroup): List<DashSection> = TV

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

    /** Where focus starts in the flyout: the current entry, else the first. */
    fun flyoutStart(entries: List<DashSection>, active: DashSection?): Int = entries.indexOf(active).coerceAtLeast(0)

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

internal data class DashFlyoutEntry(val section: DashSection, val label: String, val locked: Boolean)

/**
 * The small submenu right of TV / Library in the drawer. Up/Down choose (stopping at the ends),
 * OK opens, Left and Back close it back to the item. Focus starts on the current entry.
 */
@Composable
internal fun DashFlyout(
    entries: List<DashFlyoutEntry>,
    active: DashSection?,
    anchorPx: androidx.compose.ui.geometry.Offset,
    onClose: () -> Unit,
    onChoose: (DashFlyoutEntry) -> Unit,
) {
    val focus = remember(entries.size) { List(entries.size) { FocusRequester() } }
    val start = DashGroups.flyoutStart(entries.map { it.section }, active)
    LaunchedEffect(Unit) { focus[start].requestWhenReady() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.foundation.layout.Column(
        Modifier
            .offset { androidx.compose.ui.unit.IntOffset(anchorPx.x.toInt() + with(density) { 6.dp.roundToPx() }, anchorPx.y.toInt()) }
            .width(220.dp)
            .background(EnigmaColors.Raised, skinShape(12.dp))
            .border(1.dp, EnigmaColors.CardBorder, skinShape(12.dp))
            .padding(6.dp)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        entries.forEachIndexed { index, entry ->
            val interaction = remember { MutableInteractionSource() }
            val focused by interaction.collectIsFocusedAsState()
            val selected = entry.section == active
            Row(
                Modifier
                    .fillMaxWidth()
                    .focusRequester(focus[index])
                    .focusProperties {
                        right = FocusRequester.Cancel
                        left = FocusRequester.Cancel
                        if (index == 0) up = FocusRequester.Cancel
                        if (index == entries.lastIndex) down = FocusRequester.Cancel
                    }
                    .onPreviewKeyEvent { e ->
                        val confirm = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                        when {
                            confirm -> { if (e.type == KeyEventType.KeyUp) onChoose(entry); true }
                            e.key == Key.DirectionLeft -> { if (e.type == KeyEventType.KeyDown) onClose(); true }
                            else -> false
                        }
                    }
                    .focusable(interactionSource = interaction)
                    .background(if (focused) EnigmaColors.Focus else androidx.compose.ui.graphics.Color.Transparent, skinShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    entry.label,
                    color = if (focused) EnigmaColors.OnFocus else if (selected) EnigmaColors.Accent else EnigmaColors.Text,
                    fontSize = 14.sp,
                    fontWeight = if (selected) EnigmaType.Strong else null,
                    modifier = Modifier.weight(1f),
                )
                if (entry.locked) Icon(Icons.Filled.Lock, contentDescription = null, tint = if (focused) EnigmaColors.OnFocus else EnigmaColors.Muted, modifier = Modifier.size(14.dp))
            }
        }
    }
}
