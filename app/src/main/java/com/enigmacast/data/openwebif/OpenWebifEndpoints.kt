package com.enigmacast.data.openwebif

import com.enigmacast.domain.model.PowerAction
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.model.SleepTimerAction
import com.enigmacast.domain.osd.GrabFormat
import com.enigmacast.domain.osd.GrabMode
import com.enigmacast.domain.remote.EnigmaKey
import com.enigmacast.domain.remote.KeyAction
import java.net.URLEncoder

/**
 * Single place for OpenWebif paths.
 *
 * JSON lives under `/api/`. The older `/web/` routes return XML and are not used.
 * `/grab` is served at the web root, not under `/api`.
 * The MPEG-TS server on the streaming port (usually 8001) is separate from OpenWebif
 * and stays HTTP even when the web interface uses HTTPS.
 * Remote long-press is `type=long`. OpenWebif completes each command as down+up.
 */
@javax.inject.Singleton
class OpenWebifEndpoints @javax.inject.Inject constructor() {
    fun webifBase(config: ReceiverConfig): String {
        val scheme = if (config.useHttps) "https" else "http"
        val defaultPort = if (config.useHttps) 443 else 80
        val port = if (config.httpPort == defaultPort) "" else ":${config.httpPort}"
        return "$scheme://${config.host}$port"
    }

    fun api(config: ReceiverConfig, pathAndQuery: String): String {
        val path = if (pathAndQuery.startsWith("/")) pathAndQuery else "/$pathAndQuery"
        return webifBase(config) + path
    }

    fun about(config: ReceiverConfig): String = api(config, "/api/about")

    fun statusInfo(config: ReceiverConfig): String = api(config, "/api/statusinfo")

    fun current(config: ReceiverConfig): String = api(config, "/api/getcurrent")

    /** Signal of the receiver's current frontend: `tunersignal` on openATV and OpenPLi, `signal` on some images. */
    fun signalPaths(config: ReceiverConfig): List<String> = listOf(api(config, "/api/tunersignal"), api(config, "/api/signal"))

    fun epgService(config: ReceiverConfig, serviceReference: String): String {
        return api(config, "/api/epgservice?sRef=${encode(serviceReference)}")
    }

    fun powerState(config: ReceiverConfig, newState: Int? = null): String {
        val query = if (newState == null) "" else "?newstate=$newState"
        return api(config, "/api/powerstate$query")
    }

    fun powerState(config: ReceiverConfig, action: PowerAction): String = powerState(config, action.newState)

    fun sleepTimer(config: ReceiverConfig): String = api(config, "/api/sleeptimer?cmd=get")

    /** OpenWebif reads `enabled` as the literal text `True`/`False` and clamps `time` to 0..999 minutes. */
    fun setSleepTimer(config: ReceiverConfig, minutes: Int, action: SleepTimerAction, enabled: Boolean): String {
        val time = minutes.coerceIn(0, 999)
        val flag = if (enabled) "True" else "False"
        return api(config, "/api/sleeptimer?cmd=set&time=$time&action=${action.wire}&enabled=$flag")
    }

    fun volume(config: ReceiverConfig): String = api(config, "/api/vol")

    fun bouquets(config: ReceiverConfig): String = api(config, "/api/bouquets")

    /** `picon=1` makes OpenWebif add each channel's picon path (or its default picon). */
    fun services(config: ReceiverConfig, bouquetReference: String): String {
        val encoded = encode(bouquetReference)
        return api(config, "/api/getservices?sRef=$encoded&picon=1")
    }

    /** Without `sRef` OpenWebif lists the TV bouquets with `startpos`, the number before each bouquet's first channel. */
    fun bouquetStartPositions(config: ReceiverConfig): String = api(config, "/api/getservices")

    fun timerList(config: ReceiverConfig): String = api(config, "/api/timerlist")

    /** The programme on air for every channel of one bouquet, in a single request. */
    fun epgNow(config: ReceiverConfig, bouquetReference: String): String {
        return api(config, "/api/epgnow?bRef=${encode(bouquetReference)}")
    }

    /**
     * The programmes of every channel in a bouquet from [startSec] on. OpenWebif reads
     * `endTime` as minutes after `time` and includes the programme already running at `time`.
     */
    fun epgMulti(config: ReceiverConfig, bouquetReference: String, startSec: Long, minutes: Int): String {
        return api(config, "/api/epgmulti?bRef=${encode(bouquetReference)}&time=$startSec&endTime=$minutes")
    }

    /** Records one programme with the receiver's default recording settings. */
    fun timerAddByEventId(config: ReceiverConfig, serviceReference: String, eventId: Int): String {
        return api(config, "/api/timeraddbyeventid?sRef=${encode(serviceReference)}&eventid=$eventId")
    }

    /** A path from OpenWebif such as `/picon/1_0_1_….png`, on the web interface host. */
    fun picon(config: ReceiverConfig, path: String): String = api(config, path)

    fun zap(config: ReceiverConfig, serviceReference: String): String {
        return api(config, "/api/zap?sRef=${encode(serviceReference)}")
    }

    fun remoteControl(config: ReceiverConfig, key: EnigmaKey, action: KeyAction): String {
        val type = if (action == KeyAction.LONG) "&type=long" else ""
        return api(config, "/api/remotecontrol?command=${key.code}$type")
    }

    fun grab(config: ReceiverConfig, format: GrabFormat, mode: GrabMode): String {
        return api(config, "/grab?format=${format.wire}&mode=${mode.wire}")
    }

    fun stream(config: ReceiverConfig, serviceReference: String): String {
        return "http://${config.host}:${config.streamingPort}/${encode(serviceReference)}"
    }

    /**
     * OpenWebif's playlist for phones: the stream URL with the transcoding port and
     * parameters when the receiver can transcode. OpenWebif does not check the reference,
     * so a placeholder is enough to learn the port.
     */
    fun phonePlaylist(config: ReceiverConfig, serviceReference: String = PLACEHOLDER_REFERENCE): String =
        api(config, "/web/stream.m3u?ref=${encode(serviceReference)}&device=phone")

    /**
     * Enigma2's stream server checks `Authorization: Basic` against the receiver's system
     * account when "stream authentication" is on, and ignores the header otherwise. The
     * same user and password as for OpenWebif are sent whenever a user name is set.
     */
    fun streamAuthorization(config: ReceiverConfig): String? {
        if (config.username.isEmpty()) return null
        return okhttp3.Credentials.basic(config.username, config.password)
    }

    fun encode(value: String): String {
        // Charset overload exists only on API 33+. The string overload works from API 1.
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    private companion object {
        const val PLACEHOLDER_REFERENCE = "1:0:1:0:0:0:0:0:0:0:"
    }
}
