package com.enigmacast.data.profile

import com.enigmacast.data.db.IptvLibraryDao
import com.enigmacast.data.db.ResumeDao
import com.enigmacast.data.db.SyncOutboxDao
import com.enigmacast.data.security.ProfilePinLock
import com.enigmacast.data.tmdb.HomeBouquetStore
import com.enigmacast.data.xtream.IptvHiddenCategories
import com.enigmacast.domain.profile.Profile
import com.enigmacast.domain.profile.ProfileState
import javax.inject.Inject
import javax.inject.Singleton

/** Removes one viewer's rows when their profile is deleted. Shared recordings and receivers stay. */
@Singleton
class ProfileCleanup @Inject constructor(
    private val library: IptvLibraryDao,
    private val resume: ResumeDao,
    private val outbox: SyncOutboxDao,
    private val hidden: IptvHiddenCategories,
    private val bouquets: HomeBouquetStore,
    private val pins: ProfilePinLock,
    private val tombstones: com.enigmacast.data.xtream.LibraryTombstones,
    private val photos: AvatarPhotos,
) {
    suspend fun purge(profile: Profile) {
        val uuid = profile.uuid.ifBlank { ProfileState.LEGACY_UUID }
        library.deleteFavoritesFor(uuid)
        library.deleteResumesFor(uuid)
        outbox.deleteProfile(uuid)
        if (profile.id != ProfileState.DEFAULT_ID) resume.deletePrefix("${profile.id}|")
        hidden.purge(profile.id)
        bouquets.purge(profile.id)
        pins.clear(uuid)
        tombstones.clearProfile(uuid)
        photos.delete(uuid)
    }
}
