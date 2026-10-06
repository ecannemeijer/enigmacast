package com.enigmacast.domain.tuner

/** One tuner slot from OpenWebif `/api/about`; the texts are empty when the tuner is idle. */
data class TunerSlot(
    val index: Int,
    val name: String,
    val type: String,
    val live: String = "",
    val recording: String = "",
    val stream: String = "",
)

/** One client of the receiver's stream server (`/api/about` → `streams`). */
data class StreamClient(
    val reference: String,
    val name: String,
    val address: String,
    val tunerNumber: Int? = null,
    val transcoding: Boolean = false,
)

/**
 * What the receiver is doing with its tuners right now.
 * [liveReference] is the service on the receiver's own screen; it only uses a tuner while
 * the receiver is on ([inStandby] false). [recordingReferences] come from running timers.
 */
data class TunerSnapshot(
    val tuners: List<TunerSlot>,
    val streams: List<StreamClient> = emptyList(),
    val inStandby: Boolean? = null,
    val liveReference: String = "",
    val recordingReferences: List<String> = emptyList(),
)

enum class TunerUserKind { LIVE, RECORDING, STREAM }

/** Who holds a tuner. [name] is the channel or client name, [address] the client's address for streams. */
data class TunerUser(val kind: TunerUserKind, val name: String, val address: String = "")

sealed interface TunerVerdict {
    /** IPTV or a file: no tuner involved. */
    data object NotNeeded : TunerVerdict

    /** OpenWebif listed no tuners; let the stream try and rely on the stream check. */
    data object Unknown : TunerVerdict

    data class Free(val free: Int, val total: Int, val sharedTransponder: Boolean = false) : TunerVerdict

    data class Full(val used: Int, val total: Int, val users: List<TunerUser>) : TunerVerdict
}

/**
 * Decides whether one more direct stream can get a tuner.
 *
 * Counted as busy: the receiver's own picture while it is on, recordings, and streams of
 * other devices. This device's own streams are left out, because the app releases its
 * stream before starting the next one. A service on a transponder that is already tuned
 * shares that tuner and is always allowed. When the information is incomplete, the
 * estimate errs towards allowing the stream; the stream start check then catches a refusal.
 */
object TunerAvailability {
    fun evaluate(snapshot: TunerSnapshot, requestedReference: String, ownAddresses: Set<String>): TunerVerdict {
        if (!ServiceRef.needsTuner(requestedReference)) return TunerVerdict.NotNeeded
        if (snapshot.tuners.isEmpty()) return TunerVerdict.Unknown
        val usage = usage(snapshot, requestedReference, ownAddresses)
        val requestedTransponder = ServiceRef.transponderKey(requestedReference)
        if (requestedTransponder != null && requestedTransponder in usage.transponders) {
            return TunerVerdict.Free(free = usage.free, total = usage.total, sharedTransponder = true)
        }
        return if (usage.free > 0) {
            TunerVerdict.Free(usage.free, usage.total)
        } else {
            TunerVerdict.Full(usage.used, usage.total, usage.users)
        }
    }

    /**
     * For when the receiver refused a stream that the check let through: all tuners that
     * can receive [requestedReference] are taken, so report them all as used, with the
     * users that can be identified.
     */
    fun refused(snapshot: TunerSnapshot?, requestedReference: String, ownAddresses: Set<String>): TunerVerdict.Full {
        if (snapshot == null || snapshot.tuners.isEmpty()) return TunerVerdict.Full(0, 0, emptyList())
        val usage = usage(snapshot, requestedReference, ownAddresses)
        return TunerVerdict.Full(usage.total, usage.total, usage.users)
    }

    private class Usage(
        val total: Int,
        val used: Int,
        val users: List<TunerUser>,
        val transponders: Set<String>,
    ) {
        val free get() = total - used
    }

    private fun usage(snapshot: TunerSnapshot, requestedReference: String, ownAddresses: Set<String>): Usage {

        val delivery = ServiceRef.delivery(requestedReference)
        val eligible = snapshot.tuners.filter { ServiceRef.tunerSupports(it.type, delivery) }
            .ifEmpty { snapshot.tuners }
        val eligibleIndices = eligible.map { it.index }.toSet()
        val total = eligible.size

        val own = ownAddresses.map(ServiceRef::normalizeAddress).toSet()
        fun isOwn(client: StreamClient) = ServiceRef.normalizeAddress(client.address) in own
        val ownStreams = snapshot.streams.filter(::isOwn)
        val otherStreams = snapshot.streams.filterNot(::isOwn)
        val receiverOn = snapshot.inStandby != true

        val users = mutableListOf<TunerUser>()
        val busy = mutableSetOf<Int>()
        for (slot in eligible) {
            val liveHere = receiverOn && slot.live.isNotBlank()
            val recordingAddress = bracketAddress(slot.recording)
            val recordingIsOwnStream = recordingAddress != null && recordingAddress in own
            val recordingIsOtherStream = recordingAddress != null &&
                otherStreams.any { ServiceRef.normalizeAddress(it.address) == recordingAddress }
            val recordingHere = slot.recording.isNotBlank() && !recordingIsOwnStream
            val otherStreamHere = otherStreams.any { it.tunerNumber == slot.index }
            val ownStreamOnly = slot.stream.isNotBlank() &&
                ownStreams.any { it.tunerNumber == slot.index } && !otherStreamHere
            val streamHere = otherStreamHere || (slot.stream.isNotBlank() && !ownStreamOnly)
            if (liveHere) users += TunerUser(TunerUserKind.LIVE, slot.live)
            if (recordingHere && !recordingIsOtherStream) {
                users += TunerUser(TunerUserKind.RECORDING, slot.recording)
            }
            if (liveHere || recordingHere || streamHere) busy += slot.index
        }
        otherStreams.forEach { client ->
            users += TunerUser(TunerUserKind.STREAM, client.name, ServiceRef.normalizeAddress(client.address))
        }

        val inUseReferences = buildList {
            if (receiverOn && snapshot.liveReference.isNotBlank()) add(snapshot.liveReference)
            addAll(snapshot.recordingReferences)
            otherStreams.filter { it.tunerNumber == null || it.tunerNumber in eligibleIndices }
                .forEach { add(it.reference) }
        }
        val transponders = inUseReferences.mapNotNull { reference ->
            if (delivery != ServiceRef.Delivery.UNKNOWN && ServiceRef.delivery(reference) != delivery) null
            else ServiceRef.transponderKey(reference)
        }.toSet()

        val used = maxOf(busy.size, transponders.size).coerceAtMost(total)
        return Usage(total, used, users.distinct(), transponders)
    }

    /**
     * E2OpenPlugins images (OpenPLi and others) list a streaming tuner as a recording
     * labelled "19.2°E / Name (ip)"; returns that ip, normalised.
     */
    private fun bracketAddress(text: String): String? {
        if (text.isBlank()) return null
        val inBrackets = Regex("""\(([0-9a-fA-F:.]+)\)\s*$""").find(text)?.groupValues?.get(1) ?: return null
        return ServiceRef.normalizeAddress(inBrackets)
    }
}
