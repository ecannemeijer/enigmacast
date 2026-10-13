package com.enigmacast.domain.settingsimport

/** What a receiver load is doing right now. */
enum class ImportStage { CONNECT, BACKUP, UPLOAD, DELETE, RELOAD, SYNC }

/** One step inside a receiver load; [total] is 0 when the step has no countable parts. */
data class ImportStep(val stage: ImportStage, val done: Int = 0, val total: Int = 0)

object ImportProgress {
    /** Share of one receiver's load (0..1) reached at [step]. */
    fun receiverFraction(step: ImportStep): Float {
        val part = if (step.total > 0) step.done.coerceIn(0, step.total).toFloat() / step.total else 0f
        return when (step.stage) {
            ImportStage.CONNECT -> 0.02f
            ImportStage.BACKUP -> 0.05f + 0.30f * part
            ImportStage.UPLOAD -> 0.35f + 0.45f * part
            ImportStage.DELETE -> 0.80f + 0.05f * part
            ImportStage.RELOAD -> 0.88f
            ImportStage.SYNC -> 0.95f
        }.coerceIn(0f, 1f)
    }

    /** Overall share (0..1) when receiver [index] of [count] is at [step]. */
    fun overall(index: Int, count: Int, step: ImportStep?): Float {
        if (count <= 0) return 0f
        val inner = step?.let(::receiverFraction) ?: 0f
        return ((index.coerceIn(0, count) + inner) / count).coerceIn(0f, 1f)
    }
}
