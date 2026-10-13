package com.enigmacast.domain.settingsimport

/** What a receiver load is doing right now. */
/** What a receiver load is doing right now; EPG runs last, after the bouquets are live. */
enum class ImportStage { CONNECT, BACKUP, UPLOAD, DELETE, RELOAD, SYNC, EPG }

/** One step inside a receiver load; [total] is 0 when the step has no countable parts. */
data class ImportStep(
    val stage: ImportStage,
    val done: Int = 0,
    val total: Int = 0,
    /** EPG: the guide file being read (e.g. "IT1"), its download share 0..1 and programmes kept so far. */
    val label: String = "",
    val part: Float = 0f,
    val count: Int = 0,
)

object ImportProgress {
    /** Share of one receiver's load (0..1) reached at [step]. */
    fun receiverFraction(step: ImportStep): Float {
        val part = if (step.total > 0) step.done.coerceIn(0, step.total).toFloat() / step.total else 0f
        return when (step.stage) {
            ImportStage.CONNECT -> 0.02f
            ImportStage.BACKUP -> 0.05f + 0.25f * part
            ImportStage.UPLOAD -> 0.30f + 0.35f * part
            ImportStage.DELETE -> 0.65f + 0.05f * part
            ImportStage.RELOAD -> 0.72f
            ImportStage.SYNC -> 0.78f
            ImportStage.EPG -> 0.80f + 0.20f * epgPart(step)
        }.coerceIn(0f, 1f)
    }

    /** EPG share 0..1: finished files plus the download share of the running ones. */
    fun epgPart(step: ImportStep): Float =
        if (step.total <= 0) 0f else ((step.done + step.part.coerceIn(0f, 1f)) / step.total).coerceIn(0f, 1f)

    /** Overall share (0..1) when receiver [index] of [count] is at [step]. */
    fun overall(index: Int, count: Int, step: ImportStep?): Float {
        if (count <= 0) return 0f
        val inner = step?.let(::receiverFraction) ?: 0f
        return ((index.coerceIn(0, count) + inner) / count).coerceIn(0f, 1f)
    }
}
