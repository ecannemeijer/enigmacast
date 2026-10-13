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
import kotlinx.coroutines.ensureActive
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

    suspend fun scan(urls: List<String>, links: List<EpgLink>, nowSec: Long = System.currentTimeMillis() / 1000): IptvEpgScan =
        withContext(Dispatchers.IO) {
            val fromSec = nowSec - 2 * 3600
            val toSec = nowSec + WINDOW_SEC
            val matches = LinkedHashMap<String, String>()
            val programmes = ArrayList<Pair<String, XmltvProgramme>>()
            val used = ArrayList<String>()
            for (url in urls) {
                ensureActive()
                val pending = links.filter { it.reference !in matches }
                if (pending.isEmpty()) break
                try {
                    val fileMatches = HashMap<String, String>()
                    val refsById = HashMap<String, MutableList<String>>()
                    open(url).use { stream ->
                        val channels = LinkedHashMap<String, List<String>>()
                        XmltvReader(InputStreamReader(stream, Charsets.UTF_8)).read(
                            onChannel = { id, names -> channels[id] = names },
                            onChannelsDone = {
                                fileMatches += IptvEpg.match(pending, channels)
                                fileMatches.forEach { (ref, id) -> refsById.getOrPut(id) { mutableListOf() } += ref }
                                fileMatches.isNotEmpty()
                            },
                            wanted = { it in refsById },
                            onProgramme = { p ->
                                if (p.stopSec > fromSec && p.startSec < toSec && programmes.size < MAX_PROGRAMMES) {
                                    refsById[p.channel]?.forEach { ref -> programmes += ref to p }
                                }
                            },
                        )
                    }
                    if (fileMatches.isNotEmpty()) {
                        matches += fileMatches
                        used += url
                    }
                    EnigmaLog.service("iptv epg ${url.substringAfterLast('/')}: ${fileMatches.size} channels")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    EnigmaLog.service("iptv epg ${url.substringAfterLast('/')} failed: ${e.javaClass.simpleName}")
                }
            }
            IptvEpgScan(used, matches, programmes)
        }

    /** Stores the programmes for [receiver] (replacing earlier ones) and puts them in the guide cache. */
    suspend fun store(receiverKey: String, scan: IptvEpgScan) {
        val rows = scan.programmes.map { (ref, p) ->
            IptvEpgRow(receiverKey, NowEvents.key(ref), ref, p.startSec, p.stopSec, p.title, p.desc)
        }.distinctBy { it.serviceKey to it.beginSec }
        dao.replaceIptvEpg(receiverKey, rows, rows.map(CacheMappers::iptvGuideRow))
    }

    suspend fun clear(receiverKey: String) = dao.deleteIptvEpg(receiverKey)

    private fun open(url: String): InputStream {
        val response = http.newCall(Request.Builder().url(url).header("User-Agent", "EnigmaCast").build()).execute()
        if (!response.isSuccessful) {
            response.close()
            throw java.io.IOException("HTTP ${response.code}")
        }
        val raw = BufferedInputStream(response.body.byteStream(), 64 * 1024)
        raw.mark(2)
        val b1 = raw.read()
        val b2 = raw.read()
        raw.reset()
        // Gzip by magic bytes, whatever the URL or the server says.
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(raw, 64 * 1024) else raw
    }

    private companion object {
        const val WINDOW_SEC = 3L * 24 * 3600
        /** Hard cap so a huge guide cannot fill the TV's memory. */
        const val MAX_PROGRAMMES = 60_000
    }
}
