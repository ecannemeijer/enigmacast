package com.enigmacast.domain.osd

import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.source.OsdSource
import com.enigmacast.util.EnigmaLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class OsdController(
    private val source: OsdSource,
    private val analyzer: OsdAnalyzer,
    private val configProvider: () -> ReceiverConfig?,
    private val osdConfigProvider: () -> OsdConfig = { OsdConfig() },
    private val formatProvider: () -> GrabFormat = { GrabFormat.PNG },
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private var machine = OsdStateMachine(osdConfigProvider(), now)
    private val nudge = Channel<Unit>(Channel.CONFLATED)
    private val _state = MutableStateFlow(OsdPollState.IDLE)
    val state: StateFlow<OsdPollState> = _state.asStateFlow()
    private val _update = MutableStateFlow<OsdUpdate?>(null)
    val update: StateFlow<OsdUpdate?> = _update.asStateFlow()
    private val _latencyMs = MutableStateFlow(0L)
    val latencyMs: StateFlow<Long> = _latencyMs.asStateFlow()
    private val _fps = MutableStateFlow(0f)
    val framesPerSecond: StateFlow<Float> = _fps.asStateFlow()

    private var lastHash: Long? = null
    private var frameCount = 0
    private var fpsWindowStart = now()

    fun onRemoteInput() {
        machine = machineIfConfigChanged()
        machine.onRemoteInput()
        _state.value = machine.state
        nudge.trySend(Unit)
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        var immediate = true
        while (isActive) {
            machine = machineIfConfigChanged()
            val wait = if (immediate) {
                immediate = false
                0L
            } else {
                machine.nextDelayMs()
            }
            if (wait > 0) {
                withTimeoutOrNull(wait) { nudge.receive() }
            }
            pollOnce()
        }
    }

    private fun machineIfConfigChanged(): OsdStateMachine {
        return machine
    }

    private suspend fun pollOnce() {
        val config = configProvider() ?: return
        val started = now()
        val grabbed = try {
            source.grab(config, formatProvider(), GrabMode.OSD)
        } catch (error: Exception) {
            EnigmaLog.osd("grab failed: ${error.javaClass.simpleName}")
            return
        }
        _latencyMs.value = grabbed.durationMs.takeIf { it > 0 } ?: (now() - started)
        val analysis = try {
            analyzer.analyze(grabbed.bytes)
        } catch (error: Exception) {
            EnigmaLog.osd("decode failed: ${error.javaClass.simpleName}")
            return
        }
        val changed = analysis.hash != lastHash
        if (changed) {
            lastHash = analysis.hash
            noteFrame()
        }
        machine.onSample(changed, analysis.isEmpty)
        _state.value = machine.state
        if (changed || analysis.isEmpty) {
            EnigmaLog.osd(
                "grab ${grabbed.bytes.size}B ${analysis.width}x${analysis.height} empty=${analysis.isEmpty}",
            )
            _update.value = OsdUpdate(
                hash = analysis.hash,
                width = analysis.width,
                height = analysis.height,
                isEmpty = analysis.isEmpty,
                durationMs = _latencyMs.value,
                state = machine.state,
            )
        }
    }

    private fun noteFrame() {
        val time = now()
        if (time - fpsWindowStart >= 1_000) {
            _fps.value = frameCount * 1000f / (time - fpsWindowStart).coerceAtLeast(1)
            frameCount = 0
            fpsWindowStart = time
        }
        frameCount += 1
    }
}
