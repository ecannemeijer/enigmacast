package com.enigmacast.ui.settingsimport

import androidx.compose.foundation.background
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
import androidx.compose.ui.focus.focusRequester
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
            .padding(horizontal = 36.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PanelHeader(stringResource(R.string.import_title), stringResource(R.string.import_hint))
        Row(Modifier.fillMaxWidth().focusGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val tabs = listOf(
                Triple(ImportTab.LIST, EnigmaIcons.Layers, R.string.import_tab_lists),
                Triple(ImportTab.IPTV, EnigmaIcons.Tv, R.string.import_tab_iptv),
                Triple(ImportTab.RESTORE, EnigmaIcons.History, R.string.import_tab_restore),
                Triple(ImportTab.ACCESS, EnigmaIcons.Lock, R.string.import_tab_access),
            )
            tabs.forEachIndexed { index, (target, icon, label) ->
                ActionTile(
                    icon,
                    stringResource(label),
                    { viewModel.showTab(target) },
                    modifier = Modifier.weight(1f).then(if (index == 0) Modifier.focusRequester(firstTab) else Modifier),
                    tone = if (viewModel.tab == target) TileTone.Primary else TileTone.Normal,
                )
            }
            ActionTile(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), onBack, modifier = Modifier.weight(0.8f))
        }
        if (viewModel.receivers.isEmpty()) {
            Text(stringResource(R.string.no_receiver), color = EnigmaColors.Text, fontSize = 16.sp)
            return@Column
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun androidx.compose.foundation.layout.RowScope.ListTab(viewModel: SettingsImportViewModel) {
    Column(Modifier.weight(1f).fillMaxHeight().focusGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ChoiceRow(
            title = stringResource(R.string.import_provider),
            options = SettingsProvider.entries.map { it.title },
            selectedIndex = viewModel.provider.ordinal,
            onSelect = { viewModel.chooseProvider(SettingsProvider.entries[it]) },
        )
        when {
            viewModel.packagesLoading -> Muted(stringResource(R.string.import_loading))
            viewModel.packagesError != null -> {
                FailureText(viewModel.packagesError!!)
                TvButton(stringResource(R.string.import_retry), { viewModel.loadPackages(reload = true) })
            }
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).focusRestorer(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(viewModel.packages, key = { it.id }) { pkg ->
                ListItem(
                    selected = viewModel.selected?.id == pkg.id,
                    onClick = { viewModel.select(pkg) },
                    headlineContent = { Text(pkg.displayName, fontSize = 15.sp) },
                    supportingContent = { Text(stringResource(R.string.import_package_line, pkg.tvBouquets, pkg.radioBouquets), fontSize = 12.sp) },
                )
            }
        }
    }
    Column(
        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).focusGroup(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val pkg = viewModel.selected
        SettingsCard(stringResource(R.string.import_preview)) {
            if (pkg == null) {
                Muted(stringResource(R.string.import_choose_list))
            } else {
                Text(pkg.displayName, color = EnigmaColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                InfoRow(stringResource(R.string.import_source), "${pkg.provider.title} · ${pkg.folder}")
                InfoRow(stringResource(R.string.import_updated), viewModel.selectedDate?.let { DATE.format(it.atZone(ZoneId.systemDefault())) } ?: "–")
                InfoRow(stringResource(R.string.import_tv_bouquets), pkg.tvBouquets.toString())
                InfoRow(stringResource(R.string.import_radio_bouquets), pkg.radioBouquets.toString())
                InfoRow(stringResource(R.string.import_satellites_xml), stringResource(if (pkg.hasSatellitesXml) R.string.import_yes else R.string.import_no))
            }
        }
        RefreshChoice(viewModel.listRefresh, viewModel::changeListRefresh)
        TvButton(stringResource(R.string.import_load_list), viewModel::askLoadPackage, enabled = pkg != null && !viewModel.busy)
        Results(viewModel)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun androidx.compose.foundation.layout.RowScope.IptvTab(viewModel: SettingsImportViewModel) {
    Column(Modifier.weight(1f).fillMaxHeight().focusGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        IptvSource.entries.forEach { source ->
            ToggleRow(source.title, source in viewModel.iptvSources, { viewModel.toggleSource(source) })
        }
        TvButton(stringResource(R.string.import_load_groups), viewModel::loadGroups, enabled = viewModel.iptvSources.isNotEmpty() && !viewModel.groupsLoading)
        when {
            viewModel.groupsLoading -> Muted(stringResource(R.string.import_loading))
            viewModel.groupsError != null -> FailureText(viewModel.groupsError!!)
        }
        if (viewModel.groups.isNotEmpty()) {
            TvTextField(stringResource(R.string.import_filter_groups), viewModel.groupFilter, viewModel::setFilter)
            LazyColumn(Modifier.fillMaxWidth().weight(1f).focusRestorer(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(viewModel.visibleGroups(), key = { it.name }) { group ->
                    ToggleRow("${group.name} (${group.count})", group.name in viewModel.chosenGroups, { viewModel.toggleGroup(group.name) })
                }
            }
        }
    }
    Column(
        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).focusGroup(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SettingsCard(stringResource(R.string.import_iptv_summary)) {
            Muted(stringResource(R.string.import_iptv_explain))
            InfoRow(stringResource(R.string.import_groups_chosen), viewModel.chosenGroups.size.toString())
            if (viewModel.groups.isNotEmpty()) InfoRow(stringResource(R.string.import_channels), viewModel.iptvChannelCount().toString())
        }
        ChoiceRow(
            title = stringResource(R.string.import_service_type),
            description = stringResource(R.string.import_service_type_desc),
            options = IptvBouquets.SERVICE_TYPES.map { it.toString() },
            selectedIndex = IptvBouquets.SERVICE_TYPES.indexOf(viewModel.serviceType).coerceAtLeast(0),
            onSelect = { viewModel.changeServiceType(IptvBouquets.SERVICE_TYPES[it]) },
        )
        RefreshChoice(viewModel.iptvRefresh, viewModel::changeIptvRefresh)
        TvButton(
            stringResource(R.string.import_load_iptv),
            viewModel::askLoadIptv,
            enabled = viewModel.chosenGroups.isNotEmpty() && viewModel.groups.isNotEmpty() && !viewModel.busy,
        )
        Results(viewModel)
    }
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

@Composable
private fun RefreshChoice(value: RefreshInterval, onChange: (RefreshInterval) -> Unit) {
    ChoiceRow(
        title = stringResource(R.string.import_auto_refresh),
        options = RefreshInterval.entries.map { stringResource(refreshLabel(it)) },
        selectedIndex = value.ordinal,
        onSelect = { onChange(RefreshInterval.entries[it]) },
    )
}

private fun refreshLabel(value: RefreshInterval): Int = when (value) {
    RefreshInterval.OFF -> R.string.import_refresh_off
    RefreshInterval.DAILY -> R.string.import_refresh_daily
    RefreshInterval.WEEKLY -> R.string.import_refresh_7
    RefreshInterval.TWO_WEEKS -> R.string.import_refresh_14
    RefreshInterval.MONTHLY -> R.string.import_refresh_month
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
