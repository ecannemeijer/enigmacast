package com.enigmacast.domain.reminder

/** Where AutoSwitch reminders survive an app restart. */
interface ReminderStorage {
    suspend fun load(): List<Reminder>
    suspend fun save(reminders: List<Reminder>)
}
