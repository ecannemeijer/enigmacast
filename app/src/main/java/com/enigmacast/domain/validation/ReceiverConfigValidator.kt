package com.enigmacast.domain.validation

import com.enigmacast.domain.model.ReceiverConfig

data class ValidationResult(
    val errors: List<String>,
) {
    val isValid: Boolean get() = errors.isEmpty()
}

object ReceiverConfigValidator {
    private val hostPattern = Regex("^[A-Za-z0-9._\\-]+$")

    fun validate(config: ReceiverConfig): ValidationResult {
        val errors = mutableListOf<String>()
        if (config.name.isBlank()) errors += "Receiver name is required"
        if (config.host.isBlank() || !hostPattern.matches(config.host)) {
            errors += "Enter a valid IP address or hostname"
        }
        if (config.httpPort !in 1..65535) errors += "OpenWebif port must be between 1 and 65535"
        if (config.streamingPort !in 1..65535) errors += "Streaming port must be between 1 and 65535"
        if (config.timeoutSeconds !in 1..60) errors += "Timeout must be between 1 and 60 seconds"
        return ValidationResult(errors)
    }
}
