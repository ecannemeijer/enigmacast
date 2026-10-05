package com.enigmacast.data.openwebif

import com.enigmacast.domain.model.ReceiverConfig
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

    fun epgService(config: ReceiverConfig, serviceReference: String): String {
        return api(config, "/api/epgservice?sRef=${encode(serviceReference)}")
    }

    fun powerState(config: ReceiverConfig, newState: Int? = null): String {
        val query = if (newState == null) "" else "?newstate=$newState"
        return api(config, "/api/powerstate$query")
    }

    fun volume(config: ReceiverConfig): String = api(config, "/api/vol")

    fun bouquets(config: ReceiverConfig): String = api(config, "/api/bouquets")

    fun services(config: ReceiverConfig, bouquetReference: String): String {
        val encoded = encode(bouquetReference)
        return api(config, "/api/getservices?sRef=$encoded")
    }

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

    fun encode(value: String): String {
        // Charset overload exists only on API 33+. The string overload works from API 1.
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
