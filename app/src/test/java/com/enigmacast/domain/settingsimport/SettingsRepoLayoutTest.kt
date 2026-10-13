package com.enigmacast.domain.settingsimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepoLayoutTest {
    private fun blob(path: String) = SettingsRepoLayout.TreeEntry(path, "blob")
    private fun tree(path: String) = SettingsRepoLayout.TreeEntry(path, "tree")
    private fun folder(name: String, sat: Boolean = true, userbouquets: Int = 3) = buildList {
        add(tree(name))
        listOf("lamedb", "bouquets.tv", "bouquets.radio", "blacklist", "whitelist").forEach { add(blob("$name/$it")) }
        if (sat) add(blob("$name/satellites.xml"))
        repeat(userbouquets) { add(blob("$name/userbouquet.b$it.tv")) }
        add(blob("$name/userbouquet.r.radio"))
    }

    @Test fun morpheusFolders() {
        val entries = listOf(blob("ReadMe.txt")) + folder("morph883_13e-19.2e") + folder("morph883_motor")
        val packages = SettingsRepoLayout.packages(SettingsProvider.MORPHEUS, entries)
        assertEquals(listOf("morph883_13e-19.2e", "morph883_motor"), packages.map { it.folder })
        val p = packages[0]
        assertEquals("13E · 19.2E", p.displayName)
        assertEquals(3, p.tvBouquets)
        assertEquals(1, p.radioBouquets)
        assertTrue(p.hasSatellitesXml)
        assertEquals("Motor", packages[1].displayName)
        assertEquals("lamedb", p.uploadFiles.last())
        assertTrue(p.uploadFiles.first().startsWith("userbouquet."))
    }

    @Test fun ciefpSortsBySatelliteCount() {
        val entries = folder("ciefp-E2-10sat-39E-28E") + folder("ciefp-E2-2satA-19E-13E") + folder("ciefp-E2-motor-75E-34W") + blob("logo.png")
        val names = SettingsRepoLayout.packages(SettingsProvider.CIEFP, entries).map { it.folder }
        assertEquals(listOf("ciefp-E2-2satA-19E-13E", "ciefp-E2-10sat-39E-28E", "ciefp-E2-motor-75E-34W"), names)
        assertEquals("2satA · 19E · 13E", SettingsRepoLayout.displayName(SettingsProvider.CIEFP, "ciefp-E2-2satA-19E-13E"))
    }

    @Test fun hansSkipsToolingAndHasNoSatellites() {
        val entries = folder("e2_hanssettings_19e_23e", sat = false) +
            listOf(tree(".github"), blob(".github/workflows/ovbot.yml"), tree("CI"), blob("CI/build.sh"), blob("cleanup.sh")) +
            folder("e2_hanssettings_9e_13e_19e_23e_28e_AND_rotating", sat = false)
        val packages = SettingsRepoLayout.packages(SettingsProvider.HANS, entries)
        assertEquals(2, packages.size)
        assertFalse(packages[0].hasSatellitesXml)
        assertEquals("19E · 23E", packages[0].displayName)
        assertEquals("9E · 13E · 19E · 23E · 28E + Rotating", packages[1].displayName)
    }

    @Test fun folderWithoutLamedbIsNotAList() {
        val entries = listOf(tree("docs"), blob("docs/bouquets.tv"), blob("docs/readme.md"))
        assertTrue(SettingsRepoLayout.packages(SettingsProvider.MORPHEUS, entries).isEmpty())
    }

    @Test fun rawUrlsAreEncoded() {
        assertEquals(
            "https://raw.githubusercontent.com/morpheus883/enigma2-settings/master/morph883_13e/userbouquet.a%20b.tv",
            SettingsProvider.MORPHEUS.rawUrl("morph883_13e/userbouquet.a b.tv"),
        )
    }

    @Test fun settingsFileRules() {
        assertTrue(SettingsFiles.isSettingsFile("userbouquet.x.radio"))
        assertFalse(SettingsFiles.isSettingsFile("userbouquet.x.tv.del"))
        assertFalse(SettingsFiles.isSettingsFile("settings"))
        assertEquals("/etc/tuxbox/satellites.xml", SettingsFiles.remotePath("satellites.xml"))
        assertEquals("/etc/enigma2/lamedb", SettingsFiles.remotePath("lamedb"))
    }

    @Test fun refreshIntervals() {
        val day = RefreshInterval.DAY_MS
        assertFalse(RefreshInterval.OFF.isDue(0, 100 * day))
        assertTrue(RefreshInterval.DAILY.isDue(0, 1))
        assertTrue(RefreshInterval.DAILY.isDue(10 * day, 11 * day - 30 * 60_000))
        assertFalse(RefreshInterval.WEEKLY.isDue(10 * day, 15 * day))
        assertTrue(RefreshInterval.TWO_WEEKS.isDue(10 * day, 24 * day))
        assertFalse(RefreshInterval.MONTHLY.isDue(10 * day, 30 * day))
    }
}
