package com.enigmacast.ui.settingsimport

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.draw.clip
import com.enigmacast.domain.settingsimport.ImportStage
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import com.enigmacast.ui.theme.ChoiceChip
import com.enigmacast.ui.theme.CompactButton
import com.enigmacast.ui.theme.CompactListRow
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.data.settingsimport.IptvSource
import com.enigmacast.domain.settingsimport.IptvBouquets
import com.enigmacast.domain.settingsimport.RefreshInterval
import com.enigmacast.domain.settingsimport.SettingsProvider
import com.enigmacast.domain.settingsimport.TransferException
import com.enigmacast.domain.settingsimport.TransferFailure
import com.enigmacast.ui.theme.ActionTile
import com.enigmacast.ui.theme.ChoiceRow
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.EnigmaIcons
import com.enigmacast.ui.theme.InfoRow
import com.enigmacast.ui.theme.NavigationSoundsForWindow
import com.enigmacast.ui.theme.PanelHeader
import com.enigmacast.ui.theme.SettingsCard
import com.enigmacast.ui.theme.StatusPill
import com.enigmacast.ui.theme.TileTone
import com.enigmacast.ui.theme.ToggleRow
import com.enigmacast.ui.theme.TvButton
import com.enigmacast.ui.theme.TvConfirmDialog
import com.enigmacast.ui.theme.TvFieldKind
import com.enigmacast.ui.theme.TvTextField
import com.enigmacast.ui.theme.requestWhenReady
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Settings › Settings import: channel lists from GitHub and IPTV bouquets, loaded on the receivers. */
@Composable
fun SettingsImportScreen(
    onBack: () -> Unit,
    viewModel: SettingsImportViewModel = hiltViewModel(),
) {
    val firstTab = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstTab.requestWhenReady() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EnigmaColors.Canvas)
            .padding(horizontal = 32.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PanelHeader(stringResource(R.string.import_title), stringResource(R.string.import_hint))
        Row(Modifier.fillMaxWidth().focusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val tabs = listOf(
                Triple(ImportTab.LIST, EnigmaIcons.Layers, R.string.import_tab_lists),
                Triple(ImportTab.IPTV, EnigmaIcons.Tv, R.string.import_tab_iptv),
                Triple(ImportTab.RESTORE, EnigmaIcons.History, R.string.import_tab_restore),
                Triple(ImportTab.ACCESS, EnigmaIcons.Lock, R.string.import_tab_access),
            )
            tabs.forEachIndexed { index, (target, icon, label) ->
                CompactButton(
                    stringResource(label),
                    { viewModel.showTab(target) },
                    modifier = if (index == 0) Modifier.focusRequester(firstTab) else Modifier,
                    icon = icon,
                    selected = viewModel.tab == target,
                )
            }
            CompactButton(stringResource(R.string.back), onBack, icon = Icons.AutoMirrored.Filled.ArrowBack)
        }
        if (viewModel.receivers.isEmpty()) {
            Text(stringResource(R.string.no_receiver), color = EnigmaColors.Text, fontSize = 16.sp)
            return@Column
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            when (viewModel.tab) {
                ImportTab.LIST -> ListTab(viewModel)
                ImportTab.IPTV -> IptvTab(viewModel)
                ImportTab.RESTORE -> RestoreTab(viewModel)
                ImportTab.ACCESS -> AccessTab(viewModel)
            }
        }
    }
    if (viewModel.choosingReceivers) ReceiverChoiceDialog(viewModel)
    if (viewModel.confirming) {
        val pending = viewModel.pending
        val names = viewModel.chosenReceivers.joinToString(", ") { viewModel.receiverName(it) }
        TvConfirmDialog(
            title = stringResource(
                when (pending) {
                    is PendingLoad.Restore -> R.string.import_confirm_restore_title
                    PendingLoad.Iptv -> R.string.import_confirm_iptv_title
                    else -> R.string.import_confirm_list_title
                },
            ),
            message = when (pending) {
                is PendingLoad.Restore -> stringResource(R.string.import_confirm_restore, pending.backup.label, names)
                PendingLoad.Iptv -> stringResource(R.string.import_confirm_iptv, names)
                is PendingLoad.Package -> stringResource(R.string.import_confirm_list, pending.pkg.displayName, names)
                null -> ""
            },
            confirmLabel = stringResource(R.string.import_load),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = viewModel::confirm,
            onDismiss = viewModel::cancel,
            destructive = true,
        )
    }
}

/**
 * Focus routes between the panels. Left from the right panel goes back to the row that was
 * focused last in the list (or the list's first row), Left from the list goes to the
 * provider / source chooser, so the D-pad never hits a dead end.
 */
private class PanelFocus {
    val chooser = FocusRequester()
    val lastRow = FocusRequester()
    val panel = FocusRequester()
    var lastRowKey by mutableStateOf<String?>(null)
}

/** Fixed targets in the IPTV group column: Load groups, Select all / none and the filter. */
private class GroupFocus {
    val load = FocusRequester()
    val all = FocusRequester()
    val none = FocusRequester()
    val filter = FocusRequester()
}

/** Attaches [PanelFocus.lastRow] to the row the right panel returns to. */
private fun Modifier.listRow(focus: PanelFocus, key: String, isTarget: Boolean, up: FocusRequester? = null): Modifier =
    this
        .then(if (isTarget) Modifier.focusRequester(focus.lastRow) else Modifier)
        .onFocusChanged { if (it.isFocused) focus.lastRowKey = key }
        .focusProperties {
            left = focus.chooser
            right = focus.panel
            if (up != null) this.up = up
        }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun androidx.compose.foundation.layout.RowScope.ListTab(viewModel: SettingsImportViewModel) {
    val focus = remember { PanelFocus() }
    val packages = viewModel.packages
    val targetKey = focus.lastRowKey?.takeIf { key -> packages.any { it.id == key } }
        ?: viewModel.selected?.id?.takeIf { key -> packages.any { it.id == key } }
        ?: packages.firstOrNull()?.id
    // With no rows yet, Left from the right panel lands on the provider chooser.
    val backTarget = if (targetKey != null) focus.lastRow else focus.chooser
    Column(Modifier.weight(1f).fillMaxHeight().focusGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CompactChoice(
            title = stringResource(R.string.import_provider),
            options = SettingsProvider.entries.map { it.title },
            selectedIndex = viewModel.provider.ordinal,
            onSelect = { viewModel.chooseProvider(SettingsProvider.entries[it]) },
            selectedModifier = Modifier.focusRequester(focus.chooser),
            lastRight = focus.panel,
        )
        when {
            viewModel.packagesLoading -> Muted(stringResource(R.string.import_loading))
            viewModel.packagesError != null -> {
                FailureText(viewModel.packagesError!!)
                CompactButton(stringResource(R.string.import_retry), { viewModel.loadPackages(reload = true) })
            }
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).focusRestorer(),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            items(packages, key = { it.id }) { pkg ->
                CompactListRow(
                    onClick = { viewModel.select(pkg) },
                    selected = viewModel.selected?.id == pkg.id,
                    modifier = Modifier.listRow(focus, pkg.id, pkg.id == targetKey),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                ) { _ ->
                    Text(pkg.displayName, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.import_package_short, pkg.tvBouquets, pkg.radioBouquets), fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
    RightPanel {
        val pkg = viewModel.selected
        SettingsCard(stringResource(R.string.import_preview)) {
            if (pkg == null) {
                Muted(stringResource(R.string.import_choose_list))
            } else {
                Text(pkg.displayName, color = EnigmaColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
                InfoRow(stringResource(R.string.import_source), "${pkg.provider.title} · ${pkg.folder}")
                InfoRow(stringResource(R.string.import_updated), viewModel.selectedDate?.let { DATE.format(it.atZone(ZoneId.systemDefault())) } ?: "–")
                InfoRow(
                    stringResource(R.string.import_bouquets),
                    stringResource(
                        R.string.import_bouquets_line,
                        pkg.tvBouquets,
                        pkg.radioBouquets,
                        stringResource(if (pkg.hasSatellitesXml) R.string.import_yes else R.string.import_no),
                    ),
                )
            }
        }
        SettingsCard(null) {
            RefreshChoice(viewModel.listRefresh, viewModel::changeListRefresh, backTarget, focus.panel)
        }
        LoadButton(
            label = stringResource(R.string.import_load_list),
            onClick = viewModel::askLoadPackage,
            enabled = pkg != null && !viewModel.busy,
            left = backTarget,
            disabledHint = when {
                viewModel.busy -> stringResource(R.string.import_busy)
                pkg == null -> stringResource(R.string.import_load_disabled_list)
                else -> null
            },
        )
        LoadProgressCard(viewModel.progress)
        Results(viewModel)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun androidx.compose.foundation.layout.RowScope.IptvTab(viewModel: SettingsImportViewModel) {
    val focus = remember { PanelFocus() }
    val groups = if (viewModel.groups.isNotEmpty()) viewModel.visibleGroups() else emptyList()
    val targetKey = focus.lastRowKey?.takeIf { key -> groups.any { it.name == key } } ?: groups.firstOrNull()?.name
    val backTarget = if (targetKey != null) focus.lastRow else focus.chooser
    val leftOut = Modifier.focusProperties { right = focus.panel }
    val groupFocus = remember { GroupFocus() }
    Column(Modifier.weight(1f).fillMaxHeight().focusGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel(stringResource(R.string.import_sources_title))
        IptvSource.entries.forEachIndexed { index, source ->
            CompactToggle(
                source.title,
                source in viewModel.iptvSources,
                { viewModel.toggleSource(source) },
                modifier = (if (index == 0) Modifier.focusRequester(focus.chooser) else Modifier).then(leftOut),
            )
        }
        val hasGroups = viewModel.groups.isNotEmpty()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CompactButton(
                stringResource(R.string.import_load_groups),
                viewModel::loadGroups,
                modifier = Modifier.focusRequester(groupFocus.load).focusProperties {
                    right = focus.panel
                    if (hasGroups) down = groupFocus.all
                },
                icon = EnigmaIcons.Sync,
            )
            if (viewModel.groupsError != null && !viewModel.groupsLoading) FailureText(viewModel.groupsError!!)
        }
        if (viewModel.groupsLoading) {
            val p = viewModel.groupsProgress
            val text = if (p == null || p.done >= p.total) stringResource(R.string.import_groups_parsing)
            else stringResource(R.string.import_groups_downloading, p.done + 1, p.total, p.current)
            ProgressLine(fraction = p?.takeIf { it.total > 0 && it.done < it.total }?.let { it.done.toFloat() / it.total }, text = text)
        }
        if (hasGroups) {
            // Fixed header above the list: Select all / none and the count. Explicit Up/Down so the
            // D-pad always reaches it from "Load groups", the filter and the first group row.
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp).focusGroup(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CompactButton(
                    stringResource(R.string.import_select_all),
                    viewModel::selectAllGroups,
                    modifier = Modifier.focusRequester(groupFocus.all).focusProperties {
                        up = groupFocus.load
                        down = groupFocus.filter
                        left = FocusRequester.Cancel
                        right = groupFocus.none
                    },
                    icon = Icons.Filled.Check,
                )
                CompactButton(
                    stringResource(R.string.import_select_none),
                    viewModel::selectNoGroups,
                    modifier = Modifier.focusRequester(groupFocus.none).focusProperties {
                        up = groupFocus.load
                        down = groupFocus.filter
                        left = groupFocus.all
                        right = focus.panel
                    },
                )
                Text(
                    stringResource(R.string.import_groups_count, viewModel.chosenGroups.size, viewModel.groups.size),
                    color = EnigmaColors.Text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier.weight(1f).padding(end = 6.dp),
                )
            }
            TvTextField(
                stringResource(R.string.import_filter_groups),
                viewModel.groupFilter,
                viewModel::setFilter,
                modifier = Modifier.focusRequester(groupFocus.filter).focusProperties {
                    up = groupFocus.all
                    if (groups.isNotEmpty()) down = focus.lastRow
                    right = focus.panel
                },
            )
            val firstKey = groups.firstOrNull()?.name
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(EnigmaColors.Card, RoundedCornerShape(10.dp))
                    .border(1.dp, EnigmaColors.CardBorder, RoundedCornerShape(10.dp))
                    .padding(4.dp)
                    .focusRestorer(),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                items(groups, key = { it.name }) { group ->
                    CompactToggle(
                        group.name,
                        group.name in viewModel.chosenGroups,
                        { viewModel.toggleGroup(group.name) },
                        modifier = Modifier.listRow(focus, group.name, group.name == targetKey, up = if (group.name == firstKey) groupFocus.filter else null),
                        trailing = group.count.toString(),
                    )
                }
            }
        } else if (!viewModel.groupsLoading) {
            Muted(stringResource(R.string.import_groups_none_loaded))
        }
    }
    RightPanel {
        SettingsCard(stringResource(R.string.import_iptv_summary)) {
            Muted(stringResource(R.string.import_iptv_explain))
            InfoRow(
                stringResource(R.string.import_groups_chosen),
                if (viewModel.groups.isNotEmpty()) "${viewModel.chosenGroups.size} / ${viewModel.groups.size}" else viewModel.chosenGroups.size.toString(),
            )
            if (viewModel.groups.isNotEmpty()) InfoRow(stringResource(R.string.import_channels), viewModel.iptvChannelCount().toString())
        }
        SettingsCard(null) {
            OptionLabel(stringResource(R.string.import_service_type))
            Segmented(
                options = IptvBouquets.SERVICE_TYPES.map { it.toString() },
                selectedIndex = IptvBouquets.SERVICE_TYPES.indexOf(viewModel.serviceType).coerceAtLeast(0),
                onSelect = { viewModel.changeServiceType(IptvBouquets.SERVICE_TYPES[it]) },
                left = backTarget,
                selectedModifier = Modifier.focusRequester(focus.panel),
            )
            Muted(stringResource(R.string.import_service_type_desc))
            Spacer(Modifier.size(4.dp))
            RefreshChoice(viewModel.iptvRefresh, viewModel::changeIptvRefresh, backTarget, null)
        }
        val canLoad = viewModel.chosenGroups.isNotEmpty() && viewModel.groups.isNotEmpty() && !viewModel.busy
        LoadButton(
            label = stringResource(R.string.import_load_iptv),
            onClick = viewModel::askLoadIptv,
            enabled = canLoad,
            left = backTarget,
            disabledHint = when {
                viewModel.busy -> stringResource(R.string.import_busy)
                viewModel.groups.isEmpty() -> stringResource(R.string.import_groups_none_loaded)
                viewModel.chosenGroups.isEmpty() -> stringResource(R.string.import_load_disabled_groups)
                else -> null
            },
        )
        LoadProgressCard(viewModel.progress)
        Results(viewModel)
    }
}

/** The right-hand column: scrolls, and a focused item is brought into view automatically. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.RightPanel(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .focusGroup()
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = EnigmaColors.Accent,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 6.dp, top = 2.dp),
    )
}

@Composable
private fun OptionLabel(text: String) {
    Text(text, color = EnigmaColors.Muted, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(start = 6.dp, bottom = 2.dp))
}

/** The Load action: full width and accent coloured; when it cannot run, the reason shows under it. */
@Composable
private fun LoadButton(label: String, onClick: () -> Unit, enabled: Boolean, left: FocusRequester, disabledHint: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().focusProperties { this.left = left },
            shape = ButtonDefaults.shape(shape = RoundedCornerShape(10.dp)),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            scale = ButtonDefaults.scale(scale = 1f, focusedScale = 1.03f),
            colors = ButtonDefaults.colors(
                containerColor = EnigmaColors.Accent.copy(alpha = 0.85f),
                contentColor = EnigmaColors.OnAccent,
                focusedContainerColor = EnigmaColors.Accent,
                focusedContentColor = EnigmaColors.OnAccent,
                disabledContainerColor = EnigmaColors.Raised,
                disabledContentColor = EnigmaColors.Subtle,
            ),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(EnigmaIcons.Layers, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
        if (!enabled && disabledHint != null) {
            Text(disabledHint, color = EnigmaColors.Subtle, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/** A progress bar with its step text; [fraction] null = indeterminate. */
@Composable
private fun ProgressLine(fraction: Float?, text: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text, color = EnigmaColors.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val barModifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
        if (fraction == null) {
            LinearProgressIndicator(modifier = barModifier, color = EnigmaColors.Cyan, trackColor = EnigmaColors.Raised)
        } else {
            val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "importProgress")
            LinearProgressIndicator(progress = { animated }, modifier = barModifier, color = EnigmaColors.Cyan, trackColor = EnigmaColors.Raised)
        }
    }
}

/** Per receiver and per file while a list or bouquets are loaded on the receivers. */
@Composable
private fun LoadProgressCard(progress: LoadProgress?) {
    if (progress == null) return
    SettingsCard(stringResource(R.string.import_progress_title)) {
        val name = progress.receiverName
        val step = progress.step
        val text = when {
            name == null -> stringResource(R.string.import_progress_preparing)
            step == null -> stringResource(R.string.import_step_connect, name)
            else -> when (step.stage) {
                ImportStage.CONNECT -> stringResource(R.string.import_step_connect, name)
                ImportStage.BACKUP -> stringResource(R.string.import_step_backup, step.done, step.total, name)
                ImportStage.UPLOAD -> stringResource(R.string.import_step_upload, step.done, step.total, name)
                ImportStage.DELETE -> stringResource(R.string.import_step_delete, step.done, step.total, name)
                ImportStage.RELOAD -> stringResource(R.string.import_step_reload, name)
                ImportStage.SYNC -> stringResource(R.string.import_step_sync, name)
            }
        }
        ProgressLine(progress.fraction, text)
        if (progress.receiverCount > 1 && name != null) {
            Muted(stringResource(R.string.import_progress_receiver, progress.receiverIndex + 1, progress.receiverCount, name))
        }
    }
}

/** A single-line row with a checkbox, for dense lists. */
@Composable
private fun CompactToggle(title: String, checked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, trailing: String? = null) {
    CompactListRow(
        onClick = onToggle,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
    ) { focused ->
        CheckBox(checked, focused)
        Spacer(Modifier.width(10.dp))
        Text(title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(
                trailing,
                fontSize = 12.sp,
                color = if (focused) EnigmaColors.OnFocus else EnigmaColors.Subtle,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun CheckBox(checked: Boolean, focused: Boolean) {
    val shape = RoundedCornerShape(4.dp)
    val fill = when {
        checked && focused -> EnigmaColors.OnFocus
        checked -> EnigmaColors.Accent
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    val edge = when {
        focused -> EnigmaColors.OnFocus
        checked -> EnigmaColors.Accent
        else -> EnigmaColors.Subtle
    }
    Box(
        Modifier.size(18.dp).background(fill, shape).border(1.5.dp, edge, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Filled.Check, contentDescription = null, tint = if (focused) EnigmaColors.Focus else EnigmaColors.OnAccent, modifier = Modifier.size(14.dp))
    }
}

/** Title and choices on one line (provider chooser). */
@Composable
private fun CompactChoice(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    firstLeft: FocusRequester? = null,
    lastRight: FocusRequester? = null,
    selectedModifier: Modifier = Modifier,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = EnigmaColors.Muted, fontSize = 13.sp, maxLines = 1, modifier = Modifier.widthIn(max = 130.dp))
        options.forEachIndexed { index, label ->
            ChoiceChip(
                label = label,
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                modifier = Modifier
                    .then(if (index == selectedIndex) selectedModifier else Modifier)
                    .focusProperties {
                        if (index == 0 && firstLeft != null) left = firstLeft
                        if (index == options.lastIndex && lastRight != null) right = lastRight
                    },
            )
        }
    }
}

/**
 * Segmented control: equal-width segments on one line inside a track, so labels never wrap.
 * Left from the first segment goes to [left]; Right stops at the last one.
 */
@Composable
private fun Segmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    left: FocusRequester,
    selectedModifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(EnigmaColors.Raised, shape)
            .border(1.dp, EnigmaColors.CardBorder, shape)
            .padding(3.dp)
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Button(
                onClick = { onSelect(index) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 34.dp)
                    .then(if (selected) selectedModifier else Modifier)
                    .focusProperties {
                        if (index == 0) this.left = left
                        if (index == options.lastIndex) right = FocusRequester.Cancel
                    },
                shape = ButtonDefaults.shape(shape = RoundedCornerShape(8.dp)),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                scale = ButtonDefaults.scale(scale = 1f, focusedScale = 1.04f),
                colors = ButtonDefaults.colors(
                    containerColor = if (selected) EnigmaColors.Selected else androidx.compose.ui.graphics.Color.Transparent,
                    contentColor = if (selected) EnigmaColors.Accent else EnigmaColors.Muted,
                    focusedContainerColor = EnigmaColors.Focus,
                    focusedContentColor = EnigmaColors.OnFocus,
                    pressedContainerColor = EnigmaColors.Focus,
                    pressedContentColor = EnigmaColors.OnFocus,
                ),
            ) {
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun RefreshChoice(value: RefreshInterval, onChange: (RefreshInterval) -> Unit, left: FocusRequester, entry: FocusRequester?) {
    OptionLabel(stringResource(R.string.import_auto_refresh))
    Segmented(
        options = RefreshInterval.entries.map { stringResource(refreshShortLabel(it)) },
        selectedIndex = value.ordinal,
        onSelect = { onChange(RefreshInterval.entries[it]) },
        left = left,
        selectedModifier = if (entry != null) Modifier.focusRequester(entry) else Modifier,
    )
}

private fun refreshShortLabel(value: RefreshInterval): Int = when (value) {
    RefreshInterval.OFF -> R.string.import_refresh_off
    RefreshInterval.DAILY -> R.string.import_refresh_daily_short
    RefreshInterval.WEEKLY -> R.string.import_refresh_7_short
    RefreshInterval.TWO_WEEKS -> R.string.import_refresh_14_short
    RefreshInterval.MONTHLY -> R.string.import_refresh_month_short
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun androidx.compose.foundation.layout.RowScope.RestoreTab(viewModel: SettingsImportViewModel) {
    LazyColumn(Modifier.weight(1f).fillMaxHeight().focusRestorer(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Muted(stringResource(R.string.import_restore_hint)) }
        viewModel.receivers.forEach { receiver ->
            item(key = "h-${receiver.id}") {
                Text(receiver.name, color = EnigmaColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            }
            val list = viewModel.backupList[receiver.id].orEmpty()
            if (list.isEmpty()) {
                item(key = "e-${receiver.id}") { Muted(stringResource(R.string.import_no_backups)) }
            }
            items(list, key = { "${receiver.id}-${it.stamp}" }) { backup ->
                ListItem(
                    selected = false,
                    onClick = { if (!viewModel.busy) viewModel.askRestore(backup) },
                    headlineContent = { Text(stringResource(R.string.import_restore_item, backup.label), fontSize = 15.sp) },
                    supportingContent = { Text(stringResource(R.string.import_backup_files, backup.files.size), fontSize = 12.sp) },
                )
            }
        }
    }
    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).focusGroup()) { Results(viewModel) }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.AccessTab(viewModel: SettingsImportViewModel) {
    Column(
        Modifier.weight(1f).widthIn(max = 720.dp).fillMaxHeight().verticalScroll(rememberScrollState()).focusGroup(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Muted(stringResource(R.string.import_access_hint))
        if (viewModel.receivers.size > 1) {
            ChoiceRow(
                title = stringResource(R.string.import_receiver),
                options = viewModel.receivers.map { it.name },
                selectedIndex = viewModel.receivers.indexOfFirst { it.id == viewModel.accessReceiverId }.coerceAtLeast(0),
                onSelect = { viewModel.selectAccessReceiver(viewModel.receivers[it].id) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            TvTextField(stringResource(R.string.import_ftp_user), viewModel.ftpUser, viewModel::changeFtpUser, modifier = Modifier.weight(1f))
            TvTextField(stringResource(R.string.import_ftp_password), viewModel.ftpPassword, viewModel::changeFtpPassword, kind = TvFieldKind.PASSWORD, modifier = Modifier.weight(1f))
            TvTextField(stringResource(R.string.import_ftp_port), viewModel.ftpPort, viewModel::changeFtpPort, kind = TvFieldKind.NUMBER, modifier = Modifier.weight(0.6f))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(stringResource(R.string.import_save), viewModel::saveAccess)
            if (viewModel.accessSaved) StatusPill(stringResource(R.string.import_saved), EnigmaColors.Success)
        }
    }
}

/** Per receiver: running, loaded, or what went wrong and why. */
@Composable
private fun Results(viewModel: SettingsImportViewModel) {
    if (viewModel.status.isEmpty()) return
    SettingsCard(stringResource(R.string.import_results)) {
        viewModel.status.forEach { (id, state) ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                when (state) {
                    ReceiverStatus.Running -> {
                        StatusPill(viewModel.receiverName(id), EnigmaColors.Cyan)
                        Muted(stringResource(R.string.import_running))
                    }
                    is ReceiverStatus.Done -> {
                        val outcome = state.outcome
                        StatusPill(outcome.receiverName, if (outcome.ok) EnigmaColors.Success else EnigmaColors.Danger)
                        if (outcome.ok) {
                            Text(stringResource(R.string.import_done, outcome.bouquets), color = EnigmaColors.Text, fontSize = 14.sp)
                        } else {
                            FailureText(outcome.failure!!)
                        }
                        outcome.backupLabel?.let { Muted(stringResource(R.string.import_backup_made, it)) }
                        outcome.warnings.forEach { Muted(stringResource(R.string.import_warning, failureMessage(it))) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FailureText(error: TransferException) {
    Text(failureMessage(error), color = EnigmaColors.Danger, fontSize = 14.sp)
}

@Composable
private fun failureMessage(error: TransferException): String {
    val reason = stringResource(
        when (error.failure) {
            TransferFailure.CONNECTION_REFUSED -> R.string.import_err_refused
            TransferFailure.UNREACHABLE -> R.string.import_err_unreachable
            TransferFailure.TIMEOUT -> R.string.import_err_timeout
            TransferFailure.LOGIN_FAILED -> R.string.import_err_login
            TransferFailure.READ_FAILED -> R.string.import_err_read
            TransferFailure.BACKUP_FAILED -> R.string.import_err_backup
            TransferFailure.WRITE_FAILED -> R.string.import_err_write
            TransferFailure.RELOAD_FAILED -> R.string.import_err_reload
            TransferFailure.DOWNLOAD_FAILED -> R.string.import_err_download
            TransferFailure.NO_FILES -> R.string.import_err_no_files
            TransferFailure.UNKNOWN -> R.string.import_err_unknown
        },
    )
    return if (error.detail.isBlank()) reason else "$reason (${error.detail})"
}

@Composable
private fun Muted(text: String) {
    Text(text, color = EnigmaColors.Muted, fontSize = 13.sp, lineHeight = 17.sp)
}

/** Which receivers get the list: the active one, all, or any mix. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ReceiverChoiceDialog(viewModel: SettingsImportViewModel) {
    Dialog(onDismissRequest = viewModel::cancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        NavigationSoundsForWindow()
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { first.requestWhenReady() }
        Column(
            modifier = Modifier
                .widthIn(min = 360.dp, max = 520.dp)
                .heightIn(max = 520.dp)
                .background(EnigmaColors.Selected, RoundedCornerShape(14.dp))
                .border(1.dp, EnigmaColors.CardBorder, RoundedCornerShape(14.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.import_choose_receivers), color = EnigmaColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Muted(stringResource(R.string.import_choose_receivers_hint))
            Row(Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton(stringResource(R.string.import_active_receiver), viewModel::chooseActiveOnly, modifier = Modifier.focusRequester(first))
                TvButton(stringResource(R.string.import_all_receivers), viewModel::chooseAll)
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).focusRestorer(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(viewModel.receivers, key = { it.id }) { receiver ->
                    val label = if (receiver.id == viewModel.activeId) "${receiver.name} · ${stringResource(R.string.import_active)}" else receiver.name
                    ToggleRow(label, receiver.id in viewModel.chosenReceivers, { viewModel.toggleReceiver(receiver.id) }, description = receiver.host)
                }
            }
            Row(Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton(stringResource(R.string.import_next), viewModel::receiversChosen, enabled = viewModel.chosenReceivers.isNotEmpty())
                TvButton(stringResource(R.string.cancel), viewModel::cancel)
            }
        }
    }
}

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
