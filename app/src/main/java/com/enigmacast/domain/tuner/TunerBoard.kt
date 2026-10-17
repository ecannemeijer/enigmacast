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
            val named = streamChannelName(slot.stream)
            val clients = snapshot.streams.filter { it.tunerNumber == slot.index } +
                snapshot.streams.filter { c ->
                    // The label is "23.5°E / NPO1 HD". A shorter name such as "NPO1" must not
                    // stick to that card: it can be another transponder, which belongs on a free tuner.
                    c.tunerNumber == null && named.isNotBlank() && c.name.equals(named, ignoreCase = true)
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
        // A stream without tuner_number is not piled onto the first card. Each transponder
        // takes a card that does not already hold a different one, so a second satellite
        // shows on the next tuner instead of looking like it shares the first.
        return placeLoose(cards, snapshot.streams.filter { it !in placed }, own)
    }

    private fun placeLoose(cards: List<TunerCard>, loose: List<StreamClient>, own: Set<String>): List<TunerCard> {
        if (loose.isEmpty() || cards.isEmpty()) return cards
        val uses = cards.map { card ->
            card.uses.filterNot { it.kind == TunerUseKind.OTHER_STREAM && it.reference.isBlank() }.toMutableList()
        }
        val held = uses.map { list -> list.mapNotNull { ServiceRef.transponderKey(it.reference) }.toMutableSet() }
        loose.groupBy { ServiceRef.transponderKey(it.reference) ?: it.reference }.values.forEach { group ->
            val key = ServiceRef.transponderKey(group.first().reference)
            val slot = when {
                key != null -> held.indexOfFirst { key in it }.takeIf { it >= 0 }
                    ?: held.indexOfFirst { it.isEmpty() }.takeIf { it >= 0 }
                    ?: 0
                else -> held.indexOfFirst { it.isEmpty() }.takeIf { it >= 0 } ?: 0
            }
            if (key != null) held[slot] += key
            group.forEach { client ->
                val mine = ServiceRef.normalizeAddress(client.address) in own
                uses[slot] += TunerUse(
                    if (mine) TunerUseKind.OWN_STREAM else TunerUseKind.OTHER_STREAM,
                    client.name, client.reference, client.address,
                )
            }
        }
        return cards.mapIndexed { index, card -> card.copy(uses = uses[index]) }
    }

    fun letter(index: Int): String = if (index in 0..25) ('A' + index).toString() else (index + 1).toString()

    /** Channel name in an OpenWebif tuner text such as "23.5°E / RTL5 HD" or "RTL5 HD (ip)". */
    internal fun streamChannelName(stream: String): String =
        stream.substringAfterLast(" / ").substringBefore(" (").trim()
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

/** One card of "Scheduled today / upcoming". Times are the programme's, without box margins. */
enum class ScheduleKind { BOX_TIMER, BOX_RECORDING, TV_RECORDING, AUTOSWITCH }

/** A box timer as the box stores it (with margins): what /api/timerdelete needs. */
data class BoxTimerRef(val reference: String, val begin: Long, val end: Long)

data class ScheduleItem(
    val beginEpochSec: Long,
    val endEpochSec: Long,
    val channelName: String,
    val title: String,
    val kind: ScheduleKind,
    val running: Boolean = false,
    val reference: String = "",
    /** App recording (RecordingBook id), when the app made it. */
    val recordingId: String? = null,
    /** Box timer not made by the app. */
    val boxTimer: BoxTimerRef? = null,
    /** AutoSwitch reminder key. */
    val reminderKey: String? = null,
    /** Minutes the box adds before/after, for the optional margin note. */
    val marginBeforeMin: Int = 0,
    val marginAfterMin: Int = 0,
) {
    val id: String get() = recordingId ?: reminderKey ?: boxTimer?.let { "box|${it.reference}|${it.begin}" } ?: "$reference|$beginEpochSec|$kind"
}

object TodaySchedule {
    /** Items that begin in [dayStart, dayEnd) or are still running at [nowSec], earliest first, without duplicates. */
    fun merge(items: List<ScheduleItem>, dayStart: Long, dayEnd: Long, nowSec: Long): List<ScheduleItem> = items
        .filter { (it.beginEpochSec in dayStart until dayEnd) || (it.beginEpochSec <= nowSec && it.endEpochSec > nowSec) }
        .filter { it.endEpochSec == 0L || it.endEpochSec > dayStart }
        .distinctBy { Triple(NowEvents.key(it.reference).ifBlank { it.channelName }, it.beginEpochSec / 60, it.kind == ScheduleKind.AUTOSWITCH) }
        .sortedBy { it.beginEpochSec }
}
