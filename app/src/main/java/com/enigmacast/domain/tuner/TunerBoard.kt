package com.enigmacast.domain.tuner

/** Who is using a tuner, as shown on a Home tuner card. */
enum class TunerUseKind { LIVE, RECORDING, OWN_STREAM, OTHER_STREAM }

/**
 * One use of a tuner. [reference] is the service when OpenWebif tells it (live picture,
 * stream clients, running timers); blank when only the channel name is known.
 * [address] is the stream client's IP for streams.
 */
data class TunerUse(
    val kind: TunerUseKind,
    val channelName: String,
    val reference: String = "",
    val address: String = "",
)

data class TunerCard(
    val index: Int,
    /** "A", "B", … from the slot position. */
    val letter: String,
    val name: String,
    val type: String,
    val uses: List<TunerUse>,
) {
    val idle: Boolean get() = uses.isEmpty()
    val primary: TunerUse? get() = uses.firstOrNull()
}

/** A running timer: service and name, to name the channel a recording tuner holds. */
data class RunningTimer(val reference: String, val name: String)

/**
 * Turns OpenWebif's `/api/about` + `/api/statusinfo` snapshot into one card per tuner.
 *
 * What OpenWebif gives per tuner is text only: `live` (channel on the box's screen),
 * `rec` (recording names) and `stream` (stream names). References come from statusinfo
 * (live), the stream list (`ref`, `ip`, `tuner_number`) and running timers.
 */
object TunerBoard {
    fun cards(snapshot: TunerSnapshot, ownAddresses: Set<String>, running: List<RunningTimer> = emptyList()): List<TunerCard> {
        val own = ownAddresses.map(ServiceRef::normalizeAddress).toSet()
        val placed = mutableSetOf<StreamClient>()
        val cards = snapshot.tuners.map { slot ->
            val uses = mutableListOf<TunerUse>()
            if (snapshot.inStandby != true && slot.live.isNotBlank()) {
                uses += TunerUse(TunerUseKind.LIVE, slot.live, snapshot.liveReference)
            }
            if (slot.recording.isNotBlank()) {
                val name = slot.recording.substringBefore(" (").trim()
                val timer = running.firstOrNull { it.name.isNotBlank() && slot.recording.contains(it.name, ignoreCase = true) }
                    ?: running.firstOrNull { it.reference in snapshot.recordingReferences }?.takeIf { running.size == 1 }
                uses += TunerUse(TunerUseKind.RECORDING, timer?.name ?: name, timer?.reference.orEmpty())
            }
            val clients = snapshot.streams.filter { it.tunerNumber == slot.index } +
                snapshot.streams.filter { c ->
                    c.tunerNumber == null && slot.stream.isNotBlank() && c.name.isNotBlank() && slot.stream.contains(c.name, ignoreCase = true)
                }
            for (c in clients.distinct()) {
                placed += c
                val mine = ServiceRef.normalizeAddress(c.address) in own
                uses += TunerUse(if (mine) TunerUseKind.OWN_STREAM else TunerUseKind.OTHER_STREAM, c.name, c.reference, c.address)
            }
            if (clients.isEmpty() && slot.stream.isNotBlank()) {
                uses += TunerUse(TunerUseKind.OTHER_STREAM, slot.stream)
            }
            TunerCard(slot.index, letter(slot.index), slot.name, slot.type, uses)
        }
        // Streams OpenWebif did not tie to a tuner go on the first card that shows a stream, else the first.
        val loose = snapshot.streams.filter { it !in placed }
        if (loose.isEmpty() || cards.isEmpty()) return cards
        val target = cards.indexOfFirst { c -> c.uses.any { it.kind == TunerUseKind.OTHER_STREAM && it.reference.isBlank() } }.takeIf { it >= 0 } ?: 0
        return cards.mapIndexed { i, card ->
            if (i != target) return@mapIndexed card
            val kept = card.uses.filterNot { it.kind == TunerUseKind.OTHER_STREAM && it.reference.isBlank() }
            card.copy(uses = kept + loose.map { c ->
                val mine = ServiceRef.normalizeAddress(c.address) in own
                TunerUse(if (mine) TunerUseKind.OWN_STREAM else TunerUseKind.OTHER_STREAM, c.name, c.reference, c.address)
            })
        }
    }

    fun letter(index: Int): String = if (index in 0..25) ('A' + index).toString() else (index + 1).toString()
}

/** What "Stop stream" on a tuner card can do. */
enum class StopOption { STOP_OWN, NOT_POSSIBLE_OTHER, STANDBY_RECEIVER, NOT_POSSIBLE_RECORDING, NOTHING }

fun TunerCard.stopOption(): StopOption = when {
    uses.any { it.kind == TunerUseKind.OWN_STREAM } -> StopOption.STOP_OWN
    uses.any { it.kind == TunerUseKind.LIVE } -> StopOption.STANDBY_RECEIVER
    uses.any { it.kind == TunerUseKind.OTHER_STREAM } -> StopOption.NOT_POSSIBLE_OTHER
    uses.any { it.kind == TunerUseKind.RECORDING } -> StopOption.NOT_POSSIBLE_RECORDING
    else -> StopOption.NOTHING
}

/** One line of "Scheduled today". */
enum class ScheduleKind { BOX_TIMER, BOX_RECORDING, TV_RECORDING, AUTOSWITCH }

data class ScheduleItem(
    val beginEpochSec: Long,
    val endEpochSec: Long,
    val channelName: String,
    val title: String,
    val kind: ScheduleKind,
    val running: Boolean = false,
)

object TodaySchedule {
    /** Items that begin in [dayStart, dayEnd) or are still running at [nowSec], earliest first, without duplicates. */
    fun merge(items: List<ScheduleItem>, dayStart: Long, dayEnd: Long, nowSec: Long): List<ScheduleItem> = items
        .filter { (it.beginEpochSec in dayStart until dayEnd) || (it.beginEpochSec <= nowSec && it.endEpochSec > nowSec) }
        .filter { it.endEpochSec == 0L || it.endEpochSec > dayStart }
        .distinctBy { Triple(it.beginEpochSec, it.title.lowercase(), it.kind == ScheduleKind.AUTOSWITCH) }
        .sortedBy { it.beginEpochSec }
}
