package com.enigmacast.domain.hotel

import com.enigmacast.domain.security.PinCheck
import com.enigmacast.domain.security.PinHash
import kotlinx.serialization.Serializable

/** The hotel PIN: 4–6 digits, separate from the parental PIN, kept only as a salted PBKDF2 hash. */
object HotelPin {
    val LENGTHS = 4..6

    fun valid(pin: String) = PinHash.valid(pin, LENGTHS)

    fun hash(pin: String): String = PinHash.create(pin, lengths = LENGTHS)

    fun verify(pin: String, stored: String): Boolean = PinHash.verify(pin, stored, LENGTHS)
}

/**
 * Wrong hotel PINs: after [MAX_FAILURES] in a row the pad is locked for one minute, and every
 * further lockout doubles that, up to [MAX_LOCK_MS]. Kept across restarts, so pulling the plug
 * does not give a guest new tries. A right PIN resets everything.
 */
@Serializable
data class HotelPinAttempts(val failures: Int = 0, val lockouts: Int = 0, val lockedUntilMs: Long = 0L) {
    fun coolingDown(nowMs: Long) = nowMs < lockedUntilMs
    fun secondsLeft(nowMs: Long): Int = if (coolingDown(nowMs)) (((lockedUntilMs - nowMs) + 999) / 1000).toInt() else 0
    val left: Int get() = MAX_FAILURES - failures

    fun failed(nowMs: Long): HotelPinAttempts =
        if (failures + 1 >= MAX_FAILURES) HotelPinAttempts(0, lockouts + 1, nowMs + lockMs(lockouts))
        else copy(failures = failures + 1)

    fun succeeded() = HotelPinAttempts()

    /** Checks [pin] against [stored] (no hash = no PIN = open) and returns the result and the next state. */
    fun check(pin: String, stored: String?, nowMs: Long): Pair<PinCheck, HotelPinAttempts> {
        if (coolingDown(nowMs)) return PinCheck.CoolingDown(secondsLeft(nowMs)) to this
        if (stored == null) return PinCheck.Ok to this
        if (HotelPin.verify(pin, stored)) return PinCheck.Ok to succeeded()
        val next = failed(nowMs)
        val result = if (next.coolingDown(nowMs)) PinCheck.CoolingDown(next.secondsLeft(nowMs)) else PinCheck.Wrong(next.left)
        return result to next
    }

    companion object {
        const val MAX_FAILURES = 5
        const val FIRST_LOCK_MS = 60_000L
        const val MAX_LOCK_MS = 30 * 60_000L

        /** 1, 2, 4, 8, 16, 30, 30 … minutes for the 1st, 2nd, 3rd … lockout. */
        fun lockMs(previousLockouts: Int): Long {
            var ms = FIRST_LOCK_MS
            repeat(previousLockouts.coerceIn(0, 10)) { ms *= 2 }
            return ms.coerceAtMost(MAX_LOCK_MS)
        }
    }
}
