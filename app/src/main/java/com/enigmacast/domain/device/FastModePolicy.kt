package com.enigmacast.domain.device

import com.enigmacast.domain.model.FastMode

/** Whether the TV interface runs without animations (Settings › General › Fast mode). */
object FastModePolicy {
    /** Heap per app at or below this (MB) counts as a low-end TV box. */
    const val LOW_MEMORY_CLASS_MB = 192

    /** Few cores make every animated frame expensive. */
    const val FEW_CORES = 2

    fun isLowEnd(lowRamDevice: Boolean, memoryClassMb: Int, cores: Int): Boolean =
        lowRamDevice ||
            (memoryClassMb in 1..LOW_MEMORY_CLASS_MB) ||
            (cores in 1..FEW_CORES)

    fun isOn(mode: FastMode, lowEnd: Boolean): Boolean = when (mode) {
        FastMode.ON -> true
        FastMode.OFF -> false
        FastMode.AUTO -> lowEnd
    }
}
