package com.enigmacast.domain.settingsimport

/** How often imported lists are fetched again and sent to the receivers. */
enum class RefreshInterval(val days: Int) {
    OFF(0), DAILY(1), WEEKLY(7), TWO_WEEKS(14), MONTHLY(30);

    fun isDue(lastRunMs: Long, nowMs: Long): Boolean {
        if (this == OFF) return false
        if (lastRunMs <= 0L) return true
        // A little slack so a daily job that ran a few minutes late yesterday still runs today.
        val slack = 60L * 60 * 1000
        return nowMs - lastRunMs >= days * DAY_MS - slack
    }

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        fun byName(name: String?): RefreshInterval = entries.firstOrNull { it.name == name } ?: OFF
    }
}
