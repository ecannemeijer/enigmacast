package com.enigmacast.domain.validation

import com.enigmacast.R
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.network.WireGuardText
import com.enigmacast.util.UiText

data class ValidationResult(
    val errors: List<UiText>,
) {
    val isValid: Boolean get() = errors.isEmpty()
}

object ReceiverConfigValidator {
    private val hostPattern = Regex("^[A-Za-z0-9._\\-]+$")

    fun validate(config: ReceiverConfig): ValidationResult {
        val errors = mutableListOf<UiText>()
        if (config.name.isBlank()) errors += UiText.res(R.string.valid_name_required)
        if (config.host.isBlank() || !hostPattern.matches(config.host)) {
            errors += UiText.res(R.string.valid_host)
        }
        if (config.httpPort !in 1..65535) errors += UiText.res(R.string.valid_http_port)
        if (config.streamingPort !in 1..65535) errors += UiText.res(R.string.valid_stream_port)
        if (config.timeoutSeconds !in 1..60) errors += UiText.res(R.string.valid_timeout)
        if (config.remoteHost.isNotBlank() && !hostPattern.matches(config.remoteHost.trim())) {
            errors += UiText.res(R.string.valid_remote_host)
        }
        val remotePorts = listOf(config.remoteHttpPort, config.remoteStreamingPort, config.remoteTranscodingPort)
        if (remotePorts.any { it !in 0..65535 }) errors += UiText.res(R.string.valid_remote_port)
        if (config.transcodingPort !in 1..65535) errors += UiText.res(R.string.valid_transcoding_port)
        if (config.wireGuard && !WireGuardText.summarize(config.wireGuardConfig).valid) {
            errors += UiText.res(R.string.valid_wireguard)
        }
        return ValidationResult(errors)
    }
}
