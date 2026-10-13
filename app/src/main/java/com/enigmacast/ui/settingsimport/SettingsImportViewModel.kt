package com.enigmacast.ui.settingsimport

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.data.settingsimport.ImportConfigStore
import com.enigmacast.data.settingsimport.ImportRefreshScheduler
import com.enigmacast.data.settingsimport.IptvSource
import com.enigmacast.data.settingsimport.ReceiverListImporter
import com.enigmacast.data.settingsimport.ReceiverOutcome
import com.enigmacast.data.settingsimport.SettingsBackup
import com.enigmacast.data.settingsimport.SettingsBackupStore
import com.enigmacast.data.settingsimport.SettingsImportSources
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.settingsimport.ImportProgress
import com.enigmacast.domain.settingsimport.ImportStep
import com.enigmacast.domain.settingsimport.IptvBouquets
import com.enigmacast.domain.settingsimport.M3uEntry
import com.enigmacast.domain.settingsimport.M3uParser
import com.enigmacast.domain.settingsimport.RefreshInterval
import com.enigmacast.domain.settingsimport.SettingsProvider
import com.enigmacast.domain.settingsimport.TransferErrors
import com.enigmacast.domain.settingsimport.TransferException
import com.enigmacast.domain.settingsimport.TransferStage
import com.enigmacast.domain.settingsimport.TunerPackage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class ImportTab { LIST, IPTV, RESTORE, ACCESS }

/** What the receiver choice and the warning are for. */
sealed interface PendingLoad {
    data class Package(val pkg: TunerPackage) : PendingLoad
    data object Iptv : PendingLoad
    data class Restore(val backup: SettingsBackup) : PendingLoad
}

sealed interface ReceiverStatus {
    data object Running : ReceiverStatus
    data class Done(val outcome: ReceiverOutcome) : ReceiverStatus
}

data class GroupInfo(val name: String, val count: Int)

/** Progress of a running load. [fraction] null = indeterminate (e.g. downloading the package). */
data class LoadProgress(
    val fraction: Float?,
    val receiverName: String? = null,
    val receiverIndex: Int = 0,
    val receiverCount: Int = 0,
    val step: ImportStep? = null,
)

/** Progress of 'Load groups': playlist [done] of [total], [current] is being downloaded. */
data class GroupsProgress(val done: Int, val total: Int, val current: String)

@HiltViewModel
class SettingsImportViewModel @Inject constructor(
    private val sources: SettingsImportSources,
    private val importer: ReceiverListImporter,
    private val configs: ImportConfigStore,
    private val backups: SettingsBackupStore,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    var tab by mutableStateOf(ImportTab.LIST); private set
    var receivers by mutableStateOf<List<ReceiverConfig>>(emptyList()); private set
    var activeId by mutableStateOf<String?>(null); private set

    // Channel lists
    var provider by mutableStateOf(SettingsProvider.MORPHEUS); private set
    var packages by mutableStateOf<List<TunerPackage>>(emptyList()); private set
    var packagesLoading by mutableStateOf(false); private set
    var packagesError by mutableStateOf<TransferException?>(null); private set
    var selected by mutableStateOf<TunerPackage?>(null); private set
    var selectedDate by mutableStateOf<Instant?>(null); private set
    var listRefresh by mutableStateOf(RefreshInterval.OFF); private set

    // IPTV
    var iptvSources by mutableStateOf(setOf(IptvSource.FREE_TV)); private set
    var groups by mutableStateOf<List<GroupInfo>>(emptyList()); private set
    var chosenGroups by mutableStateOf<List<String>>(emptyList()); private set
    var groupFilter by mutableStateOf(""); private set
    var groupsLoading by mutableStateOf(false); private set
    var groupsError by mutableStateOf<TransferException?>(null); private set
    var groupsProgress by mutableStateOf<GroupsProgress?>(null); private set
    var serviceType by mutableStateOf(IptvBouquets.DEFAULT_SERVICE_TYPE); private set
    var iptvRefresh by mutableStateOf(RefreshInterval.OFF); private set
    private var entries: List<M3uEntry> = emptyList()

    // Flow
    var pending by mutableStateOf<PendingLoad?>(null); private set
    var choosingReceivers by mutableStateOf(false); private set
    var chosenReceivers by mutableStateOf<Set<String>>(emptySet()); private set
    var confirming by mutableStateOf(false); private set
    var status by mutableStateOf<Map<String, ReceiverStatus>>(emptyMap()); private set
    var busy by mutableStateOf(false); private set
    var progress by mutableStateOf<LoadProgress?>(null); private set
    var backupList by mutableStateOf<Map<String, List<SettingsBackup>>>(emptyMap()); private set

    // FTP login form
    var accessReceiverId by mutableStateOf<String?>(null); private set
    var ftpUser by mutableStateOf("root"); private set
    var ftpPassword by mutableStateOf(""); private set
    var ftpPort by mutableStateOf("21"); private set
    var accessSaved by mutableStateOf(false); private set

    private var dateJob: Job? = null

    init {
        viewModelScope.launch {
            val current = settingsRepository.current()
            receivers = current.receivers
            activeId = current.activeReceiver()?.id
            activeId?.let { id ->
                val config = configs.get(id)
                listRefresh = config.settingsRefresh
                iptvRefresh = config.iptvRefresh
                serviceType = config.iptvServiceType
                chosenGroups = config.iptvGroups
                config.iptvSources.mapNotNull { IptvSource.byName(it) }.toSet().takeIf { it.isNotEmpty() }?.let { iptvSources = it }
                config.settingsPackageId?.let { pid -> SettingsProvider.byName(pid.substringBefore('/'))?.let { provider = it } }
            }
            selectAccessReceiver(activeId ?: receivers.firstOrNull()?.id)
            loadPackages()
            refreshBackups()
        }
    }

    fun showTab(target: ImportTab) {
        tab = target
        if (target == ImportTab.RESTORE) refreshBackups()
    }

    // ---- Channel lists
    fun chooseProvider(target: SettingsProvider) {
        if (target == provider && packages.isNotEmpty()) return
        provider = target
        selected = null
        loadPackages()
    }

    fun loadPackages(reload: Boolean = false) {
        val wanted = provider
        packagesLoading = true
        packagesError = null
        viewModelScope.launch {
            try {
                val list = sources.packages(wanted, reload)
                if (provider == wanted) {
                    packages = list
                    val remembered = activeId?.let { configs.get(it).settingsPackageId }
                    list.firstOrNull { it.id == remembered }?.let { select(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (provider == wanted) { packages = emptyList(); packagesError = TransferErrors.map(e, TransferStage.DOWNLOAD) }
            } finally {
                if (provider == wanted) packagesLoading = false
            }
        }
    }

    fun select(pkg: TunerPackage) {
        selected = pkg
        selectedDate = null
        dateJob?.cancel()
        dateJob = viewModelScope.launch { selectedDate = sources.lastChanged(pkg) }
    }

    fun changeListRefresh(value: RefreshInterval) { listRefresh = value }

    fun askLoadPackage() {
        val pkg = selected ?: return
        begin(PendingLoad.Package(pkg))
    }

    // ---- IPTV
    fun toggleSource(source: IptvSource) {
        iptvSources = if (source in iptvSources) iptvSources - source else iptvSources + source
        groups = emptyList()
        entries = emptyList()
    }

    fun loadGroups() {
        if (iptvSources.isEmpty()) return
        groupsLoading = true
        groupsError = null
        viewModelScope.launch {
            try {
                val chosen = IptvSource.entries.filter { it in iptvSources }
                val all = mutableListOf<M3uEntry>()
                chosen.forEachIndexed { i, source ->
                    groupsProgress = GroupsProgress(i, chosen.size, source.title)
                    all += importer.playlist(source)
                }
                groupsProgress = GroupsProgress(chosen.size, chosen.size, "")
                entries = all
                groups = M3uParser.groupCounts(entries).map { (name, count) -> GroupInfo(name, count) }.sortedBy { it.name.lowercase() }
                // Freshly loaded groups all start selected; the user narrows them down.
                chosenGroups = groups.map { it.name }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                groupsError = TransferErrors.map(e, TransferStage.DOWNLOAD)
            } finally {
                groupsLoading = false
                groupsProgress = null
            }
        }
    }

    fun toggleGroup(name: String) {
        chosenGroups = if (name in chosenGroups) chosenGroups - name else chosenGroups + name
    }

    /** Selects every group that the filter shows (all groups without a filter). */
    fun selectAllGroups() {
        val visible = visibleGroups().map { it.name }
        chosenGroups = chosenGroups + visible.filter { it !in chosenGroups }
    }

    /** Clears the groups that the filter shows (all groups without a filter). */
    fun selectNoGroups() {
        val visible = visibleGroups().map { it.name }.toSet()
        chosenGroups = chosenGroups.filter { it !in visible }
    }

    fun setFilter(text: String) { groupFilter = text }

    fun visibleGroups(): List<GroupInfo> {
        val f = groupFilter.trim()
        return if (f.isEmpty()) groups else groups.filter { it.name.contains(f, ignoreCase = true) }
    }

    fun changeServiceType(value: Int) { serviceType = value }

    fun changeIptvRefresh(value: RefreshInterval) { iptvRefresh = value }

    fun iptvChannelCount(): Int = groups.filter { it.name in chosenGroups }.sumOf { it.count }

    fun askLoadIptv() {
        if (chosenGroups.isEmpty() || entries.isEmpty()) return
        begin(PendingLoad.Iptv)
    }

    // ---- Restore
    fun refreshBackups() {
        backupList = receivers.associate { it.id to backups.list(it.id) }
    }

    fun askRestore(backup: SettingsBackup) {
        pending = PendingLoad.Restore(backup)
        chosenReceivers = setOf(backup.receiverId)
        choosingReceivers = false
        confirming = true
    }

    // ---- Receivers + confirmation
    private fun begin(load: PendingLoad) {
        pending = load
        chosenReceivers = setOfNotNull(activeId ?: receivers.firstOrNull()?.id)
        if (receivers.size > 1) choosingReceivers = true else confirming = true
    }

    fun toggleReceiver(id: String) {
        chosenReceivers = if (id in chosenReceivers) chosenReceivers - id else chosenReceivers + id
    }

    fun chooseActiveOnly() { chosenReceivers = setOfNotNull(activeId) }

    fun chooseAll() { chosenReceivers = receivers.map { it.id }.toSet() }

    fun receiversChosen() {
        if (chosenReceivers.isEmpty()) return
        choosingReceivers = false
        confirming = true
    }

    fun cancel() {
        choosingReceivers = false
        confirming = false
        pending = null
    }

    fun confirm() {
        val load = pending ?: return
        val targets = receivers.filter { it.id in chosenReceivers }
        confirming = false
        pending = null
        if (targets.isEmpty()) return
        busy = true
        status = targets.associate { it.id to ReceiverStatus.Running }
        progress = LoadProgress(fraction = null, receiverCount = targets.size)
        viewModelScope.launch {
            try {
                val files = (load as? PendingLoad.Package)?.let { packageFiles(it.pkg, targets) ?: return@launch }
                val bouquets = if (load is PendingLoad.Iptv) IptvBouquets.build(entries, chosenGroups, serviceType) else emptyList()
                val now = System.currentTimeMillis()
                targets.forEachIndexed { index, receiver ->
                    val onStep: (ImportStep) -> Unit = { step ->
                        progress = LoadProgress(ImportProgress.overall(index, targets.size, step), receiver.name, index, targets.size, step)
                    }
                    progress = LoadProgress(ImportProgress.overall(index, targets.size, null), receiver.name, index, targets.size)
                    val outcome = when (load) {
                        is PendingLoad.Package -> importer.loadPackage(load.pkg, files!!, receiver, onStep)
                        PendingLoad.Iptv -> importer.loadIptv(bouquets, receiver, onStep)
                        is PendingLoad.Restore -> importer.restore(load.backup, receiver, onStep)
                    }
                    status = status + (receiver.id to ReceiverStatus.Done(outcome))
                    if (outcome.ok) remember(load, receiver.id, now)
                }
                ImportRefreshScheduler.update(context, configs.state.value)
                // The channel cache is already replaced; the guide and Home films follow for the active box.
                val activeLoaded = status[activeId].let { it is ReceiverStatus.Done && it.outcome.ok }
                if (activeLoaded) com.enigmacast.sync.SyncScheduler.runNow(context, force = true)
            } finally {
                busy = false
                progress = null
                refreshBackups()
            }
        }
    }

    private suspend fun packageFiles(pkg: TunerPackage, targets: List<ReceiverConfig>): Map<String, ByteArray>? = try {
        importer.download(pkg)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val mapped = TransferErrors.map(e, TransferStage.DOWNLOAD)
        status = targets.associate { it.id to ReceiverStatus.Done(ReceiverOutcome(it.id, it.name, mapped)) }
        null
    }

    private fun remember(load: PendingLoad, receiverId: String, now: Long) {
        when (load) {
            is PendingLoad.Package -> configs.update(receiverId) {
                it.copy(settingsPackageId = load.pkg.id, settingsRefresh = listRefresh, settingsLastRunMs = now)
            }
            PendingLoad.Iptv -> configs.update(receiverId) {
                it.copy(
                    iptvSources = iptvSources.map { s -> s.name },
                    iptvGroups = chosenGroups,
                    iptvServiceType = serviceType,
                    iptvRefresh = iptvRefresh,
                    iptvLastRunMs = now,
                )
            }
            // A restored list is no longer the imported one: stop refreshing it.
            is PendingLoad.Restore -> configs.update(receiverId) { it.copy(settingsRefresh = RefreshInterval.OFF, iptvRefresh = RefreshInterval.OFF) }
        }
    }

    fun receiverName(id: String): String = receivers.firstOrNull { it.id == id }?.name ?: id

    // ---- FTP login
    fun selectAccessReceiver(id: String?) {
        val receiver = receivers.firstOrNull { it.id == id } ?: return
        accessReceiverId = receiver.id
        ftpUser = receiver.ftpUsername.ifBlank { "root" }
        ftpPassword = receiver.ftpPassword
        ftpPort = receiver.ftpPort.toString()
        accessSaved = false
    }

    fun changeFtpUser(value: String) { ftpUser = value; accessSaved = false }
    fun changeFtpPassword(value: String) { ftpPassword = value; accessSaved = false }
    fun changeFtpPort(value: String) { ftpPort = value.filter { it.isDigit() }.take(5); accessSaved = false }

    fun saveAccess() {
        val id = accessReceiverId ?: return
        val receiver = receivers.firstOrNull { it.id == id } ?: return
        val port = ftpPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 21
        viewModelScope.launch {
            val saved = receiver.copy(ftpUsername = ftpUser.trim().ifBlank { "root" }, ftpPassword = ftpPassword, ftpPort = port)
            // In place: keeps the receiver order and the active receiver.
            settingsRepository.update { current -> current.copy(receivers = current.receivers.map { if (it.id == saved.id) saved else it }) }
            receivers = receivers.map { if (it.id == saved.id) saved else it }
            ftpPort = port.toString()
            accessSaved = true
        }
    }
}
