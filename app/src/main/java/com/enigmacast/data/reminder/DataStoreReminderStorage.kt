package com.enigmacast.data.reminder

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.enigmacast.data.openwebif.openWebifJson
import com.enigmacast.domain.reminder.Reminder
import com.enigmacast.domain.reminder.ReminderStorage
import kotlinx.coroutines.flow.first

/** AutoSwitch reminders in their own DataStore file, apart from the settings. */
class DataStoreReminderStorage(context: Context) : ReminderStorage {
    private val dataStore = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile("enigmacast_reminders") },
    )
    private val key = stringPreferencesKey("reminders_json")

    override suspend fun load(): List<Reminder> {
        val raw = dataStore.data.first()[key] ?: return emptyList()
        // Parsed off the caller: the stores live in Main-bound singletons.
        return withContext(Dispatchers.Default) { runCatching { openWebifJson.decodeFromString<List<Reminder>>(raw) }.getOrDefault(emptyList()) }
    }

    override suspend fun save(reminders: List<Reminder>) {
        val text = withContext(Dispatchers.Default) { openWebifJson.encodeToString(reminders) }
        dataStore.edit { it[key] = text }
    }
}
