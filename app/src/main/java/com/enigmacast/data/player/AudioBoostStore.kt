package com.enigmacast.data.player

import com.enigmacast.data.db.AudioBoostDao
import com.enigmacast.data.db.AudioBoostRow
import com.enigmacast.data.db.SyncOutboxDao
import com.enigmacast.data.db.SyncOutboxRow
import com.enigmacast.data.enigmasync.SyncTrigger
import com.enigmacast.data.profile.ProfileStore
import com.enigmacast.domain.player.AudioBoost
import com.enigmacast.domain.sync.AudioBoostSync
import com.enigmacast.domain.sync.SyncApply
import com.enigmacast.domain.sync.SyncConflict
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Live TV audio boost per channel for the active viewer (Room, user database). A change is
 * queued in the sync outbox and pushed to the sync server; other devices take it on their
 * next pull ([applyRemote]).
 */
@Singleton
class AudioBoostStore @Inject constructor(
    private val dao: AudioBoostDao,
    private val outbox: SyncOutboxDao,
    private val profiles: ProfileStore,
    private val trigger: SyncTrigger,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** channel key → level of the active viewer. */
    val levels: StateFlow<Map<String, Int>> = combine(dao.all(), profiles.state) { rows, state ->
        rows.filter { it.profileId == state.activeUuid }.associate { it.channelKey to AudioBoost.clamp(it.level) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    fun level(channelKey: String): Int = if (channelKey.isBlank()) 0 else levels.value[channelKey] ?: 0

    fun set(channelKey: String, level: Int) {
        if (channelKey.isBlank()) return
        val profileId = profiles.state.value.activeUuid
        val clamped = AudioBoost.clamp(level)
        val now = System.currentTimeMillis()
        val changeId = UUID.randomUUID().toString()
        scope.launch {
            dao.put(AudioBoostRow(profileId, channelKey, clamped, now, changeId))
            outbox.put(
                SyncOutboxRow(changeId, profileId, AudioBoostSync.ENTITY, "upsert", AudioBoostSync.payload(channelKey, clamped, now), now, ""),
            )
            trigger.nudge()
        }
    }

    /** A change pulled from the sync server; kept only when newer than this device's row. */
    suspend fun applyRemote(profileId: String, recordKey: String, payload: String?, updatedMs: Long, changeId: String) {
        val change = AudioBoostSync.parse(recordKey, payload) ?: return
        val current = dao.get(profileId, change.channelKey)
        if (SyncConflict.decide(current?.updatedMs, current?.changeId, updatedMs, changeId) != SyncApply.ACCEPT) return
        dao.put(AudioBoostRow(profileId, change.channelKey, change.level, updatedMs, changeId))
    }
}
