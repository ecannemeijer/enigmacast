package com.enigmacast.data.enigmasync

import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.data.profile.ProfileStore
import com.enigmacast.data.tmdb.HomeBouquetStore
import com.enigmacast.data.xtream.IptvHiddenCategories
import com.enigmacast.domain.model.AppSettings
import com.enigmacast.domain.profile.ProfileCloud
import com.enigmacast.domain.profile.ProfileScope
import com.enigmacast.domain.profile.ProfileSettingsSnapshot
import com.enigmacast.domain.profile.ProfileState
import javax.inject.Inject
import javax.inject.Singleton

/** Builds the per-profile settings document the sync server stores. The IPTV login is not part of it. */
@Singleton
class ProfileSnapshotBuilder @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val profiles: ProfileStore,
    private val bouquets: HomeBouquetStore,
    private val hidden: IptvHiddenCategories,
) {
    suspend fun current(uuid: String): ProfileSettingsSnapshot? =
        forUuid(uuid, settingsRepository.current(), profiles.state.value)

    fun forUuid(uuid: String, settings: AppSettings, state: ProfileState): ProfileSettingsSnapshot? {
        val profile = state.profiles.firstOrNull { it.uuid == uuid } ?: return null
        val scope = if (profile.id == state.activeId) ProfileScope.of(settings) else state.scopes[profile.id] ?: ProfileScope()
        val bouquets = this.bouquets.export(profile.id)
        return ProfileSettingsSnapshot(
            scope = scope,
            language = profile.language,
            homeBouquets = bouquets.selected,
            homeAll = bouquets.all,
            hiddenCategories = hidden.export(profile.id),
        ).let { ProfileCloud.decode(ProfileCloud.encode(it)) }
    }
}
