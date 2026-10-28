package com.enigmacast.domain.settingsimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The OpenVisionE2 settings repositories, with folder names as they are on GitHub (October 2026). */
class SettingsRepoSourcesTest {
    private fun blob(path: String) = SettingsRepoLayout.TreeEntry(path, "blob")
    private fun tree(path: String) = SettingsRepoLayout.TreeEntry(path, "tree")

    /** A list folder as these repositories have it: plain lamedb, no satellites.xml. */
    private fun folder(name: String, extra: List<String> = emptyList()) = buildList {
        add(tree(name))
        (listOf("lamedb", "bouquets.tv", "bouquets.radio", "blacklist", "userbouquet.favourites.tv", "userbouquet.r.radio") + extra)
            .forEach { add(blob("$name/$it")) }
    }

    /** What every OpenVisionE2 repository has next to the lists. */
    private val tooling = listOf(
        tree(".github"), blob(".github/workflows/ovbot.yml"),
        tree("CI"), blob("CI/build.sh"), blob("CI/chmod.sh"), blob("CI/dos2unix.sh"),
        blob("LICENSE"), blob("README.md"),
    )

    private fun names(provider: SettingsProvider, folders: List<String>) =
        SettingsRepoLayout.packages(provider, tooling + folders.flatMap { folder(it) }).map { it.displayName }

    @Test fun pickerListsAllEightRepositories() {
        assertEquals(
            listOf("Morpheus883", "Ciefp", "Hans", "Vhannibal", "GioppyGio", "Matze", "Dona", "Predrag"),
            SettingsProvider.entries.map { it.title },
        )
        // Stored ids ("NAME/folder") must keep resolving.
        assertEquals(SettingsProvider.MORPHEUS, SettingsProvider.byName("MORPHEUS"))
        assertEquals(SettingsProvider.VHANNIBAL, SettingsProvider.byName("VHANNIBAL"))
        assertNull(SettingsProvider.byName("vhannibal"))
        SettingsProvider.entries.forEach {
            assertTrue(it.repoPath, it.treeUrl.startsWith("https://api.github.com/repos/${it.owner}/${it.repo}/git/trees/"))
        }
    }

    @Test fun vhannibalDotsAndDtt() {
        val folders = listOf(
            "vhannibal.trial", "vhannibal.hotbird", "vhannibal.hotbird.and.dtt.milano", "vhannibal.dual.feeds.and.dtt.italia",
            "vhannibal.motor", "vhannibal.quad.13e.19e.23e.28e", "vhannibal.quad.nordic", "vhannibal.motor.deutschland",
        )
        val packages = SettingsRepoLayout.packages(SettingsProvider.VHANNIBAL, tooling + folders.flatMap { folder(it) })
        assertEquals(folders.sorted(), packages.map { it.folder })
        assertEquals(
            listOf(
                "Dual Feeds + DTT Italia", "Hot Bird", "Hot Bird + DTT Milano", "Motor", "Motor Deutschland",
                "Quad 13E 19E 23E 28E", "Quad Nordic", "Trial",
            ),
            packages.map { it.displayName },
        )
    }

    @Test fun vhannibalAlternativesAreUploadedButNotDuplicates() {
        val entries = folder("vhannibal.hotbird", listOf("alternatives.WAARHD.tv", "blacklist (2)", "bouquets (2).radio", "whitelist"))
        val pkg = SettingsRepoLayout.packages(SettingsProvider.VHANNIBAL, entries).single()
        assertTrue("alternatives.WAARHD.tv" in pkg.uploadFiles)
        assertFalse("blacklist (2)" in pkg.uploadFiles)
        assertFalse("bouquets (2).radio" in pkg.uploadFiles)
        assertEquals(1, pkg.tvBouquets) // alternatives are not bouquets
        assertEquals("lamedb", pkg.uploadFiles.last())
        assertFalse(pkg.hasSatellitesXml)
    }

    @Test fun gioppyGioSortsBySatelliteCountMotorLast() {
        val names = names(
            SettingsProvider.GIOPPYGIO,
            listOf("GioppyGio_E2_Motor_75E-45W", "GioppyGio_E2_Quadri_9E+13E+16E+19E", "GioppyGio_E2_Dual_13E+19E", "GioppyGio_E2_Mono_13E", "GioppyGio_E2_Trial_5W+13E+19E"),
        )
        assertEquals(listOf("Mono · 13E", "Dual · 13E+19E", "Trial · 5W+13E+19E", "Quadri · 9E+13E+16E+19E", "Motor · 75E-45W"), names)
    }

    @Test fun matzePrefixAndCable() {
        assertEquals(
            listOf("Astra", "Astra · Hot Bird", "Kabel · Deutschland"),
            names(
                SettingsProvider.MATZE,
                listOf("enigma2-plugin-settings-matze-kabel-deutschland", "enigma2-plugin-settings-matze-astra-hb", "enigma2-plugin-settings-matze-astra"),
            ),
        )
    }

    @Test fun donaSuffixOnly() {
        assertEquals(
            listOf("19E", "19E+13E", "23.5E+19E+16E+13E+1.9E+1W", "Motor · 28E-30W"),
            names(SettingsProvider.DONA, listOf("Motor_28E-30W_Dona_E2", "23.5E+19E+16E+13E+1.9E+1W_Dona_E2", "19E+13E_Dona_E2", "19E_Dona_E2")),
        )
    }

    @Test fun predragPrefixAndSuffix() {
        assertEquals(
            listOf("13E", "19E+13E+30W", "19E+16E+13E+9E+1.9E+0.8W+4W+5W", "Motor · 42E+30W"),
            names(
                SettingsProvider.PREDRAG,
                listOf("Predrag_Motor_42E+30W_E2", "Predrag_19E+16E+13E+9E+1.9E+0.8W+4W+5W_E2", "Predrag_13E_E2", "Predrag_19E+13E+30W_E2"),
            ),
        )
    }

    @Test fun existingNamesUnchanged() {
        assertEquals("13E · 19.2E", SettingsRepoLayout.displayName(SettingsProvider.MORPHEUS, "morph883_13e-19.2e"))
        assertEquals("Motor · 75E · 34W", SettingsRepoLayout.displayName(SettingsProvider.CIEFP, "ciefp-E2-motor-75E-34W"))
        assertEquals(
            "9E · 13E · 19E · 23E · 28E + Rotating",
            SettingsRepoLayout.displayName(SettingsProvider.HANS, "e2_hanssettings_9e_13e_19e_23e_28e_AND_rotating"),
        )
    }

    @Test fun onlyToolingMeansNoLists() {
        assertTrue(SettingsRepoLayout.packages(SettingsProvider.DONA, tooling).isEmpty())
    }

    @Test fun branchIsKeptAndUrlsEncoded() {
        val pkg = SettingsRepoLayout.packages(SettingsProvider.PREDRAG, folder("Predrag_13E_E2"), branch = "main").single()
        assertEquals("main", pkg.branch)
        assertEquals("PREDRAG/Predrag_13E_E2", pkg.id)
        assertEquals(
            "https://raw.githubusercontent.com/OpenVisionE2/Predrag-settings/main/Predrag_13E_E2/lamedb",
            pkg.provider.rawUrl("${pkg.folder}/lamedb", pkg.branch),
        )
        assertEquals(
            "https://raw.githubusercontent.com/OpenVisionE2/GioppyGio-settings/master/GioppyGio_E2_Dual_13E%2B16E/lamedb",
            SettingsProvider.GIOPPYGIO.rawUrl("GioppyGio_E2_Dual_13E+16E/lamedb"),
        )
        // Lists deeper than one folder are not variants.
        assertTrue(SettingsRepoLayout.packages(SettingsProvider.MORPHEUS, folder("a/b")).isEmpty())
    }

    @Test fun githubErrors() {
        assertEquals(TransferFailure.RATE_LIMITED, TransferErrors.fromHttp(403, "0", null, "x").failure)
        assertEquals(TransferFailure.RATE_LIMITED, TransferErrors.fromHttp(429, null, "60", "x").failure)
        assertEquals(TransferFailure.DOWNLOAD_FAILED, TransferErrors.fromHttp(403, "42", null, "x").failure)
        assertEquals(TransferFailure.NOT_FOUND, TransferErrors.fromHttp(404, "59", null, "OpenVisionE2/Dona-settings").failure)
        assertEquals("OpenVisionE2/Dona-settings", TransferErrors.fromHttp(404, null, null, "OpenVisionE2/Dona-settings").detail)
        assertTrue(TransferErrors.isOffline(java.io.IOException("x", java.net.UnknownHostException("api.github.com"))))
        assertFalse(TransferErrors.isOffline(java.net.SocketTimeoutException("read")))
    }

    @Test fun alternativesFollowTheBouquetsThatUseThem() {
        val files = mapOf(
            "lamedb" to "x",
            "bouquets.tv" to "#SERVICE 1:7:1:0:0:0:0:0:0:0:FROM BOUQUET \"userbouquet.a.tv\" ORDER BY bouquet\n",
            "userbouquet.a.tv" to "#SERVICE 1:134:1:0:0:0:0:0:0:0:FROM BOUQUET \"alternatives.WAARHD.tv\" ORDER BY bouquet\n",
            "alternatives.WAARHD.tv" to "#NAME WAAR HD\n",
            "alternatives.Unused.tv" to "#NAME x\n",
        ).mapValues { it.value.toByteArray() }
        val plan = ImportPlans.settingsImport(files, listOf("alternatives.Old.tv", "userbouquet.old.tv"), null)
        assertTrue("alternatives.WAARHD.tv" in plan.uploads)
        assertFalse("alternatives.Unused.tv" in plan.uploads)
        assertEquals(listOf("userbouquet.old.tv"), plan.deletions)
        assertTrue(SettingsFiles.isEnigmaListFile("alternatives.WAARHD.tv")) // backed up
        assertEquals("/etc/enigma2/alternatives.WAARHD.tv", SettingsFiles.remotePath("alternatives.WAARHD.tv"))
    }
}
