package com.enigmacast.data.db

import com.enigmacast.domain.profile.ProfileState

/**
 * Additive user-database steps. Existing favorites and resume rows stay with the original
 * profile ([ProfileState.LEGACY_UUID]); nothing is dropped.
 */
object UserMigrations {
    fun v3ToV4(exec: (String) -> Unit) {
        val legacy = ProfileState.LEGACY_UUID
        exec(
            "CREATE TABLE IF NOT EXISTS `iptv_library_new` (`source` TEXT NOT NULL, `kind` TEXT NOT NULL, `id` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, `poster` TEXT, `backdrop` TEXT, `year` INTEGER, `categoryId` TEXT NOT NULL, " +
                "`extension` TEXT NOT NULL, `url` TEXT NOT NULL, `addedMs` INTEGER NOT NULL, `profileId` TEXT NOT NULL, " +
                "PRIMARY KEY(`source`, `kind`, `id`, `profileId`))",
        )
        exec(
            "INSERT INTO `iptv_library_new` (`source`, `kind`, `id`, `title`, `poster`, `backdrop`, `year`, `categoryId`, " +
                "`extension`, `url`, `addedMs`, `profileId`) " +
                "SELECT `source`, `kind`, `id`, `title`, `poster`, `backdrop`, `year`, `categoryId`, `extension`, `url`, `addedMs`, " +
                "'$legacy' FROM `iptv_library`",
        )
        exec("DROP TABLE `iptv_library`")
        exec("ALTER TABLE `iptv_library_new` RENAME TO `iptv_library`")
        exec(
            "CREATE TABLE IF NOT EXISTS `iptv_resume_new` (`source` TEXT NOT NULL, `kind` TEXT NOT NULL, `id` TEXT NOT NULL, " +
                "`positionMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `updatedMs` INTEGER NOT NULL, `title` TEXT NOT NULL, " +
                "`poster` TEXT, `extension` TEXT NOT NULL, `url` TEXT NOT NULL, `categoryId` TEXT NOT NULL, `seriesId` TEXT, " +
                "`seriesTitle` TEXT, `season` INTEGER NOT NULL, `episode` INTEGER NOT NULL, `profileId` TEXT NOT NULL, " +
                "PRIMARY KEY(`source`, `kind`, `id`, `profileId`))",
        )
        exec(
            "INSERT INTO `iptv_resume_new` (`source`, `kind`, `id`, `positionMs`, `durationMs`, `updatedMs`, `title`, `poster`, " +
                "`extension`, `url`, `categoryId`, `seriesId`, `seriesTitle`, `season`, `episode`, `profileId`) " +
                "SELECT `source`, `kind`, `id`, `positionMs`, `durationMs`, `updatedMs`, `title`, `poster`, `extension`, `url`, " +
                "`categoryId`, `seriesId`, `seriesTitle`, `season`, `episode`, '$legacy' FROM `iptv_resume`",
        )
        exec("DROP TABLE `iptv_resume`")
        exec("ALTER TABLE `iptv_resume_new` RENAME TO `iptv_resume`")
        exec(
            "CREATE TABLE IF NOT EXISTS `sync_outbox` (`changeId` TEXT NOT NULL, `profileId` TEXT NOT NULL, `entity` TEXT NOT NULL, " +
                "`op` TEXT NOT NULL, `payload` TEXT NOT NULL, `updatedMs` INTEGER NOT NULL, `deviceId` TEXT NOT NULL, " +
                "PRIMARY KEY(`changeId`))",
        )
        exec(
            "CREATE TABLE IF NOT EXISTS `sync_cursor` (`profileId` TEXT NOT NULL, `cursor` TEXT NOT NULL, PRIMARY KEY(`profileId`))",
        )
    }

    /** The watch history table, started from the saved resume places (nothing is lost). */
    fun v4ToV5(exec: (String) -> Unit) {
        exec(
            "CREATE TABLE IF NOT EXISTS `episode_history` (`source` TEXT NOT NULL, `kind` TEXT NOT NULL, `id` TEXT NOT NULL, " +
                "`seriesId` TEXT, `positionMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `watched` INTEGER NOT NULL, " +
                "`updatedMs` INTEGER NOT NULL, `profileId` TEXT NOT NULL, PRIMARY KEY(`source`, `kind`, `id`, `profileId`))",
        )
        exec(
            "INSERT OR IGNORE INTO `episode_history` (`source`, `kind`, `id`, `seriesId`, `positionMs`, `durationMs`, `watched`, " +
                "`updatedMs`, `profileId`) SELECT `source`, `kind`, `id`, `seriesId`, `positionMs`, `durationMs`, 0, `updatedMs`, " +
                "`profileId` FROM `iptv_resume` WHERE `positionMs` > 0",
        )
    }

    /** The live TV audio boost per channel and viewer (synced as entity "audio_boost"). */
    fun v5ToV6(exec: (String) -> Unit) {
        exec(
            "CREATE TABLE IF NOT EXISTS `audio_boost` (`profileId` TEXT NOT NULL, `channelKey` TEXT NOT NULL, " +
                "`level` INTEGER NOT NULL, `updatedMs` INTEGER NOT NULL, `changeId` TEXT NOT NULL, " +
                "PRIMARY KEY(`profileId`, `channelKey`))",
        )
    }
}
