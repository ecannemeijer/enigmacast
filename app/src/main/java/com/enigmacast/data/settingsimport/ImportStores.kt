package com.enigmacast.data.settingsimport

import android.content.Context
import com.enigmacast.domain.settingsimport.IptvBouquets
import com.enigmacast.domain.settingsimport.RefreshInterval
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What was last loaded on a receiver, so auto-refresh can do it again. */
@Serializable
data class ReceiverImportConfig(
    /** "PROVIDER/folder" of the channel list, or null. */
    val settingsPackageId: String? = null,
    val settingsRefresh: RefreshInterval = RefreshInterval.OFF,
    val settingsLastRunMs: Long = 0L,
    val iptvSources: List<String> = emptyList(),
    val iptvGroups: List<String> = emptyList(),
    val iptvServiceType: Int = IptvBouquets.DEFAULT_SERVICE_TYPE,
    val iptvRefresh: RefreshInterval = RefreshInterval.OFF,
    val iptvLastRunMs: Long = 0L,
    val lastResult: String? = null,
)

@Serializable
data class ImportConfigs(val receivers: Map<String, ReceiverImportConfig> = emptyMap())

@Singleton
class ImportConfigStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("settings_import", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(load())
    val state: StateFlow<ImportConfigs> = _state.asStateFlow()

    fun get(receiverId: String): ReceiverImportConfig = _state.value.receivers[receiverId] ?: ReceiverImportConfig()

    @Synchronized
    fun update(receiverId: String, transform: (ReceiverImportConfig) -> ReceiverImportConfig) {
        val next = _state.value.copy(receivers = _state.value.receivers + (receiverId to transform(get(receiverId))))
        prefs.edit().putString(KEY, json.encodeToString(ImportConfigs.serializer(), next)).apply()
        _state.value = next
    }

    private fun load(): ImportConfigs = prefs.getString(KEY, null)
        ?.let { runCatching { json.decodeFromString(ImportConfigs.serializer(), it) }.getOrNull() }
        ?: ImportConfigs()

    private companion object { const val KEY = "configs" }
}

/** One saved copy of a receiver's list files, taken before anything was overwritten. */
data class SettingsBackup(val receiverId: String, val stamp: String, val dir: File, val files: List<String>) {
    /** "2026-10-13 19:41". */
    val label: String get() = runCatching {
        LocalDateTime.parse(stamp, STAMP).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }.getOrDefault(stamp)

    companion object { val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss") }
}

/** Backups in app storage: files/settings-backups/<receiver id>/<yyyyMMdd-HHmmss>/. The last [KEEP] are kept. */
@Singleton
class SettingsBackupStore @Inject constructor(@ApplicationContext private val context: Context) {
    private fun root(receiverId: String) = File(File(context.filesDir, "settings-backups"), safe(receiverId))

    fun save(receiverId: String, files: Map<String, ByteArray>): SettingsBackup {
        val stamp = LocalDateTime.now().format(SettingsBackup.STAMP)
        val dir = File(root(receiverId), stamp).apply { mkdirs() }
        files.forEach { (name, bytes) -> File(dir, name.replace('/', '_')).writeBytes(bytes) }
        File(dir, ".complete").writeText(files.size.toString())
        list(receiverId).drop(KEEP).forEach { it.dir.deleteRecursively() }
        return SettingsBackup(receiverId, stamp, dir, files.keys.sorted())
    }

    /** Newest first; unfinished backups are not offered. */
    fun list(receiverId: String): List<SettingsBackup> =
        root(receiverId).listFiles { f -> f.isDirectory && File(f, ".complete").exists() }.orEmpty()
            .sortedByDescending { it.name }
            .map { dir -> SettingsBackup(receiverId, dir.name, dir, dir.listFiles().orEmpty().map { it.name }.filter { !it.startsWith(".") }.sorted()) }

    fun read(backup: SettingsBackup): Map<String, ByteArray> = backup.files.associateWith { File(backup.dir, it).readBytes() }

    private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    companion object { const val KEEP = 5 }
}
