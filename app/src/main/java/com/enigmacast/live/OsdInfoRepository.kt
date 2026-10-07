package com.enigmacast.live

import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.osdinfo.ReceiverOsdData
import com.enigmacast.domain.service.BouquetChannels
import com.enigmacast.domain.source.OsdInfoSource
import com.enigmacast.util.EnigmaLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Receiver data for the OSD info strip. [watch] polls only while it is collected, which the
 * remote screen does only while the channel banner (or quick bar) is visible: the signal every
 * [SIGNAL_INTERVAL_MS], `getcurrent` and the stream clients from `/api/about` on start and
 * every [STATIC_INTERVAL_MS]. The last result per channel is kept, so reopening the banner on
 * the same channel shows it at once.
 */
@Singleton
class OsdInfoRepository @Inject constructor(
    private val source: OsdInfoSource,
) {
    @Volatile
    private var last: Pair<String, ReceiverOsdData>? = null

    /** Last known data for [reference] (same service), or null. */
    fun cached(reference: String): ReceiverOsdData? =
        last?.takeIf { BouquetChannels.sameService(it.first, reference) }?.second

    fun watch(
        config: ReceiverConfig,
        reference: String,
        clock: () -> Long = { System.nanoTime() / 1_000_000 },
    ): Flow<ReceiverOsdData> = flow {
        var data = cached(reference) ?: ReceiverOsdData()
        emit(data)
        var staticAt: Long? = null
        var quickRetries = 0
        var loggedError = false
        while (true) {
            val now = clock()
            val ourClientMissing = data.streams != null && data.streams.none { BouquetChannels.sameService(it.reference, reference) }
            val refreshStatic = staticAt == null ||
                now - staticAt >= STATIC_INTERVAL_MS ||
                (ourClientMissing && quickRetries < QUICK_RETRIES && now - staticAt >= QUICK_RETRY_MS)
            val next = coroutineScope {
                val signal = async { attempt { source.signal(config) } }
                val current = if (refreshStatic) async { attempt { source.current(config) } } else null
                val streams = if (refreshStatic) async { attempt { source.streamClients(config) } } else null
                var result = data
                signal.await().onSuccess { reading ->
                    result = if (reading == null) {
                        result.copy(signal = null, signalUnsupported = true)
                    } else {
                        result.copy(signal = reading, signalUnsupported = false, inStandby = reading.inStandby)
                    }
                }.onFailure { error ->
                    if (!loggedError) EnigmaLog.osdInfo("signal failed: ${error.message}")
                    loggedError = true
                }
                current?.await()?.onSuccess { result = result.copy(current = it) }
                streams?.await()?.onSuccess { result = result.copy(streams = it) }
                result
            }
            if (refreshStatic) {
                if (staticAt != null && ourClientMissing) quickRetries++
                staticAt = now
                logStatic(reference, next)
            }
            data = next
            last = reference to data
            emit(data)
            delay(SIGNAL_INTERVAL_MS)
        }
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        Result.failure(error)
    }

    private fun logStatic(reference: String, data: ReceiverOsdData) {
        val client = data.streams?.firstOrNull { BouquetChannels.sameService(it.reference, reference) }
        EnigmaLog.osdInfo(
            "receiver: live=${data.current?.reference?.takeIf { it.isNotBlank() } ?: "-"} " +
                "crypt=${data.current?.crypted} standby=${data.inStandby} " +
                "streams=${data.streams?.size ?: "-"} ourClient=${client?.let { "tuner ${it.tunerNumber ?: "?"}" } ?: "-"} " +
                "signalTuner=${data.signal?.tunerNumber ?: "-"}" + if (data.signalUnsupported) " (no signal endpoint)" else "",
        )
    }

    companion object {
        const val SIGNAL_INTERVAL_MS = 2_500L
        const val STATIC_INTERVAL_MS = 15_000L
        const val QUICK_RETRY_MS = 2_000L
        const val QUICK_RETRIES = 3
    }
}
