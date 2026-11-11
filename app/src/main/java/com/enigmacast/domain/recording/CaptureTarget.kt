package com.enigmacast.domain.recording

/** Stream addresses for a capture on this device, tried in order, and the box's authorization. */
data class CaptureTarget(
    val urls: List<String>,
    val authorization: String?,
    val userAgent: String = "",
    val headers: Map<String, String> = emptyMap(),
) {
    companion object {
        /** Chosen size first, then the same size on the stream port, then the original stream. */
        fun order(primary: String, fallback: String?, direct: String?): List<String> =
            listOfNotNull(primary, fallback, direct).filter { it.isNotBlank() }.distinct()
    }
}
