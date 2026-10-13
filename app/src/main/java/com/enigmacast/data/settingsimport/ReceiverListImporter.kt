package com.enigmacast.data.settingsimport

import com.enigmacast.data.network.OkHttpClientFactory
import com.enigmacast.data.openwebif.OpenWebifEndpoints
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.settingsimport.ImportStage
import com.enigmacast.domain.settingsimport.ImportStep
import com.enigmacast.domain.settingsimport.ImportPlan
import com.enigmacast.domain.settingsimport.ImportPlans
import com.enigmacast.domain.settingsimport.IptvBouquets
import com.enigmacast.domain.settingsimport.M3uEntry
import com.enigmacast.domain.settingsimport.M3uParser
import com.enigmacast.domain.settingsimport.ReloadCheck
import com.enigmacast.domain.settingsimport.SettingsFiles
import com.enigmacast.domain.settingsimport.SettingsProvider
import com.enigmacast.domain.settingsimport.TransferErrors
import com.enigmacast.domain.settingsimport.TransferException
import com.enigmacast.domain.settingsimport.TransferFailure
import com.enigmacast.domain.settingsimport.TransferStage
import com.enigmacast.domain.settingsimport.TunerPackage
import com.enigmacast.sync.SyncEngine
import com.enigmacast.util.EnigmaLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.Request

/** Outcome on one receiver. [warnings] are things that did not stop the load (satellites.xml, app sync). */
data class ReceiverOutcome(
    val receiverId: String,
    val receiverName: String,
    val failure: TransferException? = null,
    val warnings: List<TransferException> = emptyList(),
    val bouquets: Int = 0,
    val backupLabel: String? = null,
) {
    val ok: Boolean get() = failure == null
}

/**
 * Loads channel lists and IPTV bouquets on Enigma2 receivers: back up over FTP, write the files,
 * reload the service list through OpenWebif, then refresh the app's own channel cache.
 */
@Singleton
class ReceiverListImporter @Inject constructor(
    private val sources: SettingsImportSources,
    private val backups: SettingsBackupStore,
    private val http: OkHttpClientFactory,
    private val endpoints: OpenWebifEndpoints,
    private val sync: SyncEngine,
) {
    /** One receiver at a time: two writes to the same /etc/enigma2 must never interleave. */
    private val gate = Mutex()

    suspend fun loadPackage(
        pkg: TunerPackage,
        files: Map<String, ByteArray>,
        receiver: ReceiverConfig,
        onStep: (ImportStep) -> Unit = {},
    ): ReceiverOutcome =
        run(receiver, onStep = onStep) { box, boxFiles, bouquetsTv ->
            ImportPlans.settingsImport(files, boxFiles, bouquetsTv) to pkg.tvBouquets
        }

    suspend fun loadIptv(
        bouquets: List<IptvBouquets.Bouquet>,
        receiver: ReceiverConfig,
        onStep: (ImportStep) -> Unit = {},
    ): ReceiverOutcome =
        run(receiver, onStep = onStep) { _, boxFiles, bouquetsTv ->
            ImportPlans.iptv(bouquets, boxFiles, bouquetsTv.orEmpty()) to bouquets.size
        }

    /** Puts a backup back: its files are written and list files it did not have are removed. */
    suspend fun restore(backup: SettingsBackup, receiver: ReceiverConfig, onStep: (ImportStep) -> Unit = {}): ReceiverOutcome =
        run(receiver, takeBackup = false, onStep = onStep) { _, boxFiles, _ ->
            val saved = backups.read(backup)
            val deletions = boxFiles.filter { SettingsFiles.isEnigmaListFile(it) && it !in saved }
            ImportPlan(saved, deletions) to saved.keys.count { it.startsWith("userbouquet.") && it.endsWith(".tv") }
        }

    /** Downloads a package (cached per call site). */
    suspend fun download(pkg: TunerPackage): Map<String, ByteArray> {
        val files = sources.download(pkg)
        if ("bouquets.tv" !in files || ("lamedb" !in files && "lamedb5" !in files)) {
            throw TransferException(TransferFailure.NO_FILES, pkg.folder)
        }
        return files
    }

    suspend fun findPackage(id: String): TunerPackage? {
        val provider = SettingsProvider.byName(id.substringBefore('/')) ?: return null
        return sources.packages(provider).firstOrNull { it.id == id }
    }

    /** Downloads and parses the chosen playlists. */
    suspend fun playlists(names: List<String>): List<M3uEntry> = names.mapNotNull { IptvSource.byName(it) }
        .flatMap { M3uParser.parse(sources.playlist(it)) }

    /** Downloads and parses one playlist. */
    suspend fun playlist(source: IptvSource): List<M3uEntry> = M3uParser.parse(sources.playlist(source))

    private suspend fun run(
        receiver: ReceiverConfig,
        takeBackup: Boolean = true,
        onStep: (ImportStep) -> Unit = {},
        plan: (FtpReceiverClient, List<String>, String?) -> Pair<ImportPlan, Int>,
    ): ReceiverOutcome = gate.withLock {
        val warnings = mutableListOf<TransferException>()
        var backupLabel: String? = null
        var expected: Set<String>? = null
        var removed: List<String> = emptyList()
        try {
            onStep(ImportStep(ImportStage.CONNECT))
            val count = withContext(Dispatchers.IO) {
                FtpReceiverClient.open(receiver).use { box ->
                    val boxFiles = box.list(SettingsFiles.ENIGMA_DIR).filter { SettingsFiles.isEnigmaListFile(it) }
                    // Backup first. Without a complete copy nothing is overwritten.
                    val current = LinkedHashMap<String, ByteArray>()
                    boxFiles.forEachIndexed { i, name ->
                        onStep(ImportStep(ImportStage.BACKUP, i, boxFiles.size))
                        box.download(SettingsFiles.remotePath(name))?.let { current[name] = it }
                    }
                    onStep(ImportStep(ImportStage.BACKUP, boxFiles.size, boxFiles.size))
                    if (takeBackup) {
                        if (current.isEmpty()) throw TransferException(TransferFailure.BACKUP_FAILED, SettingsFiles.ENIGMA_DIR)
                        runCatching { box.download(SettingsFiles.remotePath(SettingsFiles.SATELLITES)) }.getOrNull()
                            ?.let { current[SettingsFiles.SATELLITES] = it }
                        backupLabel = try {
                            backups.save(receiver.id, current).label
                        } catch (e: Exception) {
                            throw TransferException(TransferFailure.BACKUP_FAILED, e.message.orEmpty(), e)
                        }
                    }
                    val bouquetsTv = current["bouquets.tv"]?.toString(Charsets.UTF_8)
                    val (work, count) = plan(box, boxFiles, bouquetsTv)
                    expected = ReloadCheck.expectedFiles(work.uploads)
                    removed = work.deletions
                    val uploads = work.uploads.entries.toList()
                    uploads.forEachIndexed { i, (name, bytes) ->
                        onStep(ImportStep(ImportStage.UPLOAD, i + 1, uploads.size))
                        try {
                            box.upload(SettingsFiles.remotePath(name), bytes)
                        } catch (e: TransferException) {
                            // satellites.xml sits outside /etc/enigma2 and is read-only on some images.
                            if (name == SettingsFiles.SATELLITES) warnings += e else throw e
                        }
                    }
                    work.deletions.forEachIndexed { i, name ->
                        onStep(ImportStep(ImportStage.DELETE, i + 1, work.deletions.size))
                        box.delete(SettingsFiles.remotePath(name))
                    }
                    count
                }
            }
            onStep(ImportStep(ImportStage.RELOAD))
            reload(receiver)
            // Enigma2 reloads on its own thread: wait until OpenWebif lists the new bouquets.
            awaitReload(receiver, expected, removed)
            onStep(ImportStep(ImportStage.SYNC))
            try {
                // Forced: the cache and every screen showing this box take the new lists now.
                sync.refreshChannelsNow(receiver, force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warnings += TransferErrors.map(e, TransferStage.RELOAD).let { TransferException(TransferFailure.UNKNOWN, "app sync: ${it.detail}", e) }
            }
            EnigmaLog.service("settings import on ${receiver.name}: ok, $count bouquets")
            ReceiverOutcome(receiver.id, receiver.name, null, warnings, count, backupLabel)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val mapped = TransferErrors.map(e, TransferStage.UPLOAD)
            EnigmaLog.service("settings import on ${receiver.name}: ${mapped.failure}")
            ReceiverOutcome(receiver.id, receiver.name, mapped, warnings, backupLabel = backupLabel)
        }
    }

    /** Polls the bouquet list (up to ~20 s) until it shows [expected]; without a bouquets.tv upload a short pause. */
    private suspend fun awaitReload(receiver: ReceiverConfig, expected: Set<String>?, removed: List<String>) {
        if (expected.isNullOrEmpty()) {
            kotlinx.coroutines.delay(1_500)
            return
        }
        val client = http.webifClient(receiver.allowInsecureCertificates, receiver.timeoutSeconds)
        val path = "/api/getservices?sRef=" + java.net.URLEncoder.encode(BOUQUETS_TV_REF, "UTF-8").replace("+", "%20")
        val started = System.currentTimeMillis()
        kotlinx.coroutines.delay(500)
        while (System.currentTimeMillis() - started < 20_000) {
            val body = withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder().url(endpoints.api(receiver, path))
                        .apply { if (receiver.username.isNotEmpty()) header("Authorization", Credentials.basic(receiver.username, receiver.password)) }
                        .build()
                    client.newCall(request).execute().use { it.body.string() }
                }.getOrNull()
            }
            if (body != null && ReloadCheck.isLive(body, expected, removed)) {
                EnigmaLog.service("settings import on ${receiver.name}: lists live after ${System.currentTimeMillis() - started} ms")
                // Channels inside the bouquets are read right after; give Enigma2 a short moment more.
                kotlinx.coroutines.delay(500)
                return
            }
            kotlinx.coroutines.delay(1_000)
        }
        EnigmaLog.service("settings import on ${receiver.name}: reload not confirmed in 20 s, reading lists anyway")
    }

    /** `/api/servicelistreload?mode=0` (lamedb + bouquets); older OpenWebif only has `/web/…`. */
    suspend fun reload(receiver: ReceiverConfig) = withContext(Dispatchers.IO) {
        val client = http.webifClient(receiver.allowInsecureCertificates, receiver.timeoutSeconds)
        var last: TransferException? = null
        for (path in listOf("/api/servicelistreload?mode=0", "/web/servicelistreload?mode=0")) {
            val request = Request.Builder().url(endpoints.api(receiver, path))
                .apply { if (receiver.username.isNotEmpty()) header("Authorization", Credentials.basic(receiver.username, receiver.password)) }
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    when {
                        response.code == 401 -> throw TransferException(TransferFailure.RELOAD_FAILED, "OpenWebif login (401)")
                        response.isSuccessful && TransferErrors.reloadSucceeded(body) -> return@withContext
                        else -> last = TransferException(TransferFailure.RELOAD_FAILED, "HTTP ${response.code} ${body.take(80)}".trim())
                    }
                }
            } catch (e: TransferException) {
                throw e
            } catch (e: Exception) {
                last = TransferErrors.map(e, TransferStage.RELOAD).let {
                    if (it.failure == TransferFailure.CONNECTION_REFUSED || it.failure == TransferFailure.TIMEOUT) it
                    else TransferException(TransferFailure.RELOAD_FAILED, it.detail, e)
                }
            }
        }
        throw last ?: TransferException(TransferFailure.RELOAD_FAILED)
    }

    private companion object {
        const val BOUQUETS_TV_REF = "1:7:1:0:0:0:0:0:0:0:FROM BOUQUET \"bouquets.tv\" ORDER BY bouquet"
    }
}
