package com.enigmacast.domain.service

import com.enigmacast.domain.model.EnigmaService

data class ServiceChange(
    val previousReference: String?,
    val current: EnigmaService,
)

class ServiceChangeDetector {
    private var lastReference: String? = null

    fun onSample(service: EnigmaService?): ServiceChange? {
        val reference = service?.reference?.takeIf { it.isNotBlank() } ?: return null
        if (reference == lastReference) return null
        val previous = lastReference
        lastReference = reference
        return ServiceChange(previous, service)
    }
}
