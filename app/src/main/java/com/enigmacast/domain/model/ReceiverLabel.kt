package com.enigmacast.domain.model

/** "Octagon SF8008" from OpenWebif's brand and model; the brand is not repeated when the model already has it. */
object ReceiverLabel {
    fun of(brand: String, model: String): String {
        val b = brand.trim()
        val m = model.trim()
        return when {
            m.isBlank() -> b
            b.isBlank() || m.startsWith(b, ignoreCase = true) -> m
            else -> "$b $m"
        }
    }
}
