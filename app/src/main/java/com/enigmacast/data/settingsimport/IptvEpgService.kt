package com.enigmacast.data.settingsimport

import com.enigmacast.data.db.CacheDao
import com.enigmacast.data.db.CacheMappers
import com.enigmacast.data.db.IptvEpgRow
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.settingsimport.EpgLink
import com.enigmacast.domain.settingsimport.IptvEpg
import com.enigmacast.domain.settingsimport.XmltvProgramme
import com.enigmacast.domain.settingsimport.XmltvReader
import com.enigmacast.domain.tuner.NowEvents
import com.enigmacast.util.EnigmaLog
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import android.util.Xml
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** What the XMLTV files gave for the chosen IPTV services. */
data class IptvEpgScan(
    val urls: List<String>,
    /** Service reference (first ten fields) → XMLTV channel id. */
    val matches: Map<String, String>,
    /** Programmes by service reference, only the matched channels in the window. */
    val programmes: List<Pair<String, XmltvProgramme>>,
)

/**
 * EPG for imported IPTV bouquets: reads the playlists' XMLTV guides in one streaming pass
 * (gzip or plain), keeps only channels in the chosen bouquets and only the next days.
 */
@Singleton
class IptvEpgService @Inject constructor(private val dao: CacheDao) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Progress of a scan: [done] of [total] files finished, [label] / [part] of the newest running file, [programmes] kept. */
    data class Progress(val done: Int, val total: Int, val label: String, val part: Float, val programmes: Int)

    /**
     * Reads [urls] (at most two at a time) with Android's pull parser straight from the gzip
     * stream. Only programmes of matched channels in now-2h..+3 days are kept.
     */
    suspend fun scan(
        urls: List<String>,
        links: List<EpgLink>,
        nowSec: Long = System.currentTimeMillis() / 1000,
        onProgress: (Progress) -> Unit = {},
    ): IptvEpgScan = withContext(Dispatchers.IO) {
        val fromSec = nowSec - 2 * 3600
        val toSec = nowSec + WINDOW_SEC
        val lock = Any()
        val matches = LinkedHashMap<String, String>()
        val programmes = ArrayList<Pair<String, XmltvProgramme>>()
        val used = ArrayList<String>()
        var done = 0
        val gate = Semaphore(2)
        fun report(label: String, part: Float) {
            val p = synchronized(lock) { Progress(done, urls.size, label, part, programmes.size) }
            onProgress(p)
        }
        coroutineScope {
            urls.map { url ->
                async {
                    gate.withPermit {
                        val label = url.substringAfterLast('/').substringBefore(".xml").removePrefix("epg_ripper_")
                        val started = System.currentTimeMillis()
                        var kept = 0
                        try {
                            val fileMatches = HashMap<String, String>()
                            val refsById = HashMap<String, MutableList<String>>()
                            report(label, 0f)
                            open(url) { read, total -> if (total > 0) report(label, read.toFloat() / total) }.use { stream ->
                                val channels = HashMap<String, List<String>>()
                                XmltvReader(Xml.newPullParser(), InputStreamReader(stream, Charsets.UTF_8)).read(
                                    onChannel = { id, names -> channels[id] = names },
                                    onChannelsDone = {
                                        val pending = synchronized(lock) { links.filter { it.reference !in matches } }
                                        fileMatches += IptvEpg.match(pending, channels)
                                        fileMatches.forEach { (ref, id) -> refsById.getOrPut(id) { mutableListOf() } += ref }
                                        fileMatches.isNotEmpty()
                                    },
                                    wanted = { it in refsById },
                                    onProgramme = { p ->
                                        if (p.stopSec > fromSec && p.startSec < toSec) {
                                            refsById[p.channel]?.let { refs ->
                                                synchronized(lock) {
                                                    if (programmes.size < MAX_PROGRAMMES) refs.forEach { programmes += it to p; kept++ }
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                            synchronized(lock) {
                                fileMatches.forEach { (ref, id) -> matches.putIfAbsent(ref, id) }
                                if (fileMatches.isNotEmpty()) used += url
                            }
                            EnigmaLog.service("iptv epg $label: ${fileMatches.size} channels, $kept programmes in ${System.currentTimeMillis() - started} ms")
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            EnigmaLog.service("iptv epg $label failed after ${System.currentTimeMillis() - started} ms: ${e.javaClass.simpleName}")
                        } finally {
                            synchronized(lock) { done++ }
                            report(label, 0f)
                        }
                    }
                }
            }.awaitAll()
        }
        IptvEpgScan(urls.filter { it in used }, matches, programmes)
    }

    /** Stores the programmes for [receiver] (replacing earlier ones) and puts them in the guide cache. */
    suspend fun store(receiverKey: String, scan: IptvEpgScan) {
        val rows = scan.programmes.map { (ref, p) ->
            IptvEpgRow(receiverKey, NowEvents.key(ref), ref, p.startSec, p.stopSec, p.title, p.desc)
        }.distinctBy { it.serviceKey to it.beginSec }
        dao.replaceIptvEpg(receiverKey, rows, rows.map(CacheMappers::iptvGuideRow))
        EnigmaLog.service("iptv epg stored ${rows.size} programmes")
    }

    suspend fun clear(receiverKey: String) = dao.deleteIptvEpg(receiverKey)

    /** The (decompressed) guide; [onBytes] gets compressed bytes read and Content-Length (−1 when unknown), every 256 KB. */
    private fun open(url: String, onBytes: (Long, Long) -> Unit): InputStream {
        val response = http.newCall(Request.Builder().url(url).header("User-Agent", "EnigmaCast").build()).execute()
        if (!response.isSuccessful) {
            response.close()
            throw java.io.IOException("HTTP ${response.code}")
        }
        val total = response.body.contentLength()
        val counted = CountingInputStream(response.body.byteStream()) { read -> onBytes(read, total) }
        val raw = BufferedInputStream(counted, 64 * 1024)
        raw.mark(2)
        val b1 = raw.read()
        val b2 = raw.read()
        raw.reset()
        // Gzip by magic bytes, whatever the URL or the server says.
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(raw, 64 * 1024) else raw
    }

    /** Counts bytes and reports every 256 KB. */
    private class CountingInputStream(source: InputStream, private val report: (Long) -> Unit) : java.io.FilterInputStream(source) {
        private var count = 0L
        private var last = 0L
        private fun add(n: Long) {
            if (n <= 0) return
            count += n
            if (count - last >= 256 * 1024) { last = count; report(count) }
        }
        override fun read(): Int = super.read().also { if (it >= 0) add(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { add(it.toLong()) }
    }

    private companion object {
        const val WINDOW_SEC = 3L * 24 * 3600
        /** Hard cap so a huge guide cannot fill the TV's memory. */
        const val MAX_PROGRAMMES = 60_000
    }
}
