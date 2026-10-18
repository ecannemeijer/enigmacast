package com.enigmacast.data.recording

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.enigmacast.data.openwebif.openWebifJson
import com.enigmacast.domain.recording.Recording
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Recordings started from this app, apart from the settings file. */
@Singleton
class DataStoreRecordingStorage @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val dataStore = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile("enigmacast_recordings") },
    )
    private val key = stringPreferencesKey("recordings_json")

    suspend fun load(): List<Recording> {
        val raw = dataStore.data.first()[key] ?: return emptyList()
        // Parsed off the caller: the stores live in Main-bound singletons.
        return withContext(Dispatchers.Default) { runCatching { openWebifJson.decodeFromString<List<Recording>>(raw) }.getOrDefault(emptyList()) }
    }

    suspend fun save(recordings: List<Recording>) {
        val text = withContext(Dispatchers.Default) { openWebifJson.encodeToString(recordings) }
        dataStore.edit { it[key] = text }
    }
}
