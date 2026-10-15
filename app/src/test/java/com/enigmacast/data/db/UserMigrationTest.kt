package com.enigmacast.data.db

import com.enigmacast.domain.profile.ProfileState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.sql.DriverManager

class UserMigrationTest {
    @Test fun v3RowsStayWithTheOriginalProfile() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            val exec = db.createStatement()
            exec.executeUpdate(
                "CREATE TABLE iptv_library (source TEXT NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, title TEXT NOT NULL, " +
                    "poster TEXT, backdrop TEXT, year INTEGER, categoryId TEXT NOT NULL, extension TEXT NOT NULL, url TEXT NOT NULL, " +
                    "addedMs INTEGER NOT NULL, PRIMARY KEY(source, kind, id))",
            )
            exec.executeUpdate(
                "CREATE TABLE iptv_resume (source TEXT NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, positionMs INTEGER NOT NULL, " +
                    "durationMs INTEGER NOT NULL, updatedMs INTEGER NOT NULL, title TEXT NOT NULL, poster TEXT, extension TEXT NOT NULL, " +
                    "url TEXT NOT NULL, categoryId TEXT NOT NULL, seriesId TEXT, seriesTitle TEXT, season INTEGER NOT NULL, episode INTEGER NOT NULL, " +
                    "PRIMARY KEY(source, kind, id))",
            )
            exec.executeUpdate("INSERT INTO iptv_library VALUES ('src','m','7','Film',null,null,null,'c','mp4','',1)")
            exec.executeUpdate("INSERT INTO iptv_resume VALUES ('src','e','8',1000,9000,5,'Ep',null,'mkv','','c','s','Show',1,2)")
            UserMigrations.v3ToV4 { sql -> exec.executeUpdate(sql) }
            val favorite = exec.executeQuery("SELECT profileId, title FROM iptv_library")
            favorite.next()
            assertEquals(ProfileState.LEGACY_UUID, favorite.getString(1))
            assertEquals("Film", favorite.getString(2))
            val resume = exec.executeQuery("SELECT profileId, episode FROM iptv_resume")
            resume.next()
            assertEquals(ProfileState.LEGACY_UUID, resume.getString(1))
            assertEquals(2, resume.getInt(2))
            exec.executeQuery("SELECT changeId FROM sync_outbox")
            exec.executeQuery("SELECT cursor FROM sync_cursor")
        }
    }
}
