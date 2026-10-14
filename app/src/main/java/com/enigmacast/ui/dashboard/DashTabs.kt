package com.enigmacast.ui.dashboard

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
internal enum class DashGroup { TV, LIBRARY }

internal object DashGroups {
    private val TV = listOf(DashSection.Stream, DashSection.Guide, DashSection.Movies, DashSection.Series, DashSection.Radio)
    private val LIBRARY = listOf(DashSection.MyItems, DashSection.Search)

    fun groupOf(section: DashSection): DashGroup? = when (section) {
        in TV -> DashGroup.TV
        in LIBRARY -> DashGroup.LIBRARY
        else -> null
    }

    fun tabs(group: DashGroup): List<DashSection> = if (group == DashGroup.TV) TV else LIBRARY

    /**
     * The page OK on a group opens: the last-used tab, else the first open one. Live TV is a
     * tab that opens the full player, so the TV group opens on a page (the guide first).
     */
    fun entry(group: DashGroup, last: DashSection?, open: (DashSection) -> Boolean): DashSection {
        val pages = tabs(group).filter { it != DashSection.Stream }
        val order = listOfNotNull(last?.takeIf { it in pages }) + pages
        return order.firstOrNull(open) ?: pages.last()
    }

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

internal data class DashTab(val section: DashSection, val label: String, val focus: FocusRequester, val locked: Boolean)

/**
 * The tab row at the top of a grouped page. Left/Right walk the tabs (Left on the first goes
 * back to the main menu, Right on the last stays), Down enters the page, Up stays.
 */
@Composable
internal fun DashTabRow(
    tabs: List<DashTab>,
    active: DashSection,
    menuFocus: FocusRequester,
    onFocused: (DashTab) -> Unit,
    onUnfocused: () -> Unit,
    onClick: (DashTab) -> Unit,
    onDown: (DashTab) -> Unit,
) {
    Row(
        Modifier.padding(start = 12.dp, bottom = 12.dp).focusGroup().onFocusChanged { if (!it.hasFocus) onUnfocused() },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEachIndexed { index, tab ->
            val interaction = remember { MutableInteractionSource() }
            val focused by interaction.collectIsFocusedAsState()
            LaunchedEffect(focused) { if (focused) onFocused(tab) }
            val selected = tab.section == active
            val shape = skinShape(18.dp)
            Row(
                Modifier
                    .focusRequester(tab.focus)
                    .focusProperties {
                        up = FocusRequester.Cancel
                        if (index == 0) left = menuFocus
                        if (index == tabs.lastIndex) right = FocusRequester.Cancel
                    }
                    .onPreviewKeyEvent { e ->
                        val confirm = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                        when {
                            confirm -> { if (e.type == KeyEventType.KeyUp) onClick(tab); true }
                            e.key == Key.DirectionDown -> { if (e.type == KeyEventType.KeyDown) onDown(tab); true }
                            else -> false
                        }
                    }
                    .focusable(interactionSource = interaction)
                    .background(
                        when {
                            focused -> EnigmaColors.Focus
                            selected -> EnigmaColors.Selected
                            else -> EnigmaColors.Raised
                        },
                        shape,
                    )
                    .border(1.dp, if (focused || selected) EnigmaColors.Accent else EnigmaColors.CardBorder, shape)
                    .padding(horizontal = 16.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (tab.locked) Icon(Icons.Filled.Lock, contentDescription = null, tint = if (focused) EnigmaColors.OnFocus else EnigmaColors.Muted, modifier = Modifier.padding(end = 2.dp))
                Text(
                    tab.label,
                    color = if (focused) EnigmaColors.OnFocus else if (selected) EnigmaColors.Text else EnigmaColors.Muted,
                    fontSize = 15.sp,
                    fontWeight = if (selected) EnigmaType.Strong else null,
                )
            }
        }
    }
}
