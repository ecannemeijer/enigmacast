package com.enigmacast.companionbridge

import com.enigmacast.domain.model.AppSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Payload of [CompanionProtocol.TYPE_STATE_RECEIVER]: the receivers the TV uses, so the paired
 * phone's Live screen needs no setup. Contains the OpenWebif login: send only to authenticated
 * clients and never log it.
 */
object ReceiverShare {
    fun json(settings: AppSettings): JsonObject = buildJsonObject {
        put("activeId", settings.activeReceiver()?.id.orEmpty())
        put(
            "receivers",
            buildJsonArray {
                settings.receivers.filter { it.host.isNotBlank() }.forEach { r ->
                    add(
                        buildJsonObject {
                            put("id", r.id)
                            put("name", r.name)
                            put("host", r.host)
                            put("httpPort", r.httpPort)
                            put("useHttps", r.useHttps)
                            put("username", r.username)
                            put("password", r.password)
                            put("streamingPort", r.streamingPort)
                            put("allowInsecureCertificates", r.allowInsecureCertificates)
                            put("timeoutSeconds", r.timeoutSeconds)
                        },
                    )
                }
            },
        )
    }
}
