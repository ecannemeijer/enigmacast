package com.enigmacast.domain.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StremioManifestTest {
    private fun err(body: String) = (StremioManifest.parse(body).exceptionOrNull() as ManifestException).error

    @Test
    fun normalizesAddresses() {
        val u = StremioManifest.normalize(" stremio://addon.example/opts=a|b/manifest.json ")!!
        assertEquals("https://addon.example/opts=a|b/manifest.json", u.manifest)
        assertEquals("https://addon.example/opts=a|b", u.base)
        assertEquals("https://addon.example/manifest.json", StremioManifest.normalize("https://addon.example/")!!.manifest)
        assertEquals("http://10.0.0.2:7000/manifest.json", StremioManifest.normalize("http://10.0.0.2:7000")!!.manifest)
        assertNull(StremioManifest.normalize(""))
        assertNull(StremioManifest.normalize("ftp://addon.example/manifest.json"))
        assertNull(StremioManifest.normalize("addon.example/manifest.json"))
        assertNull(StremioManifest.normalize("https:///manifest.json"))
    }

    @Test
    fun masksTheConfigurationPath() {
        val masked = StremioManifest.mask("https://addon.example/debridkey=SECRET123/manifest.json")
        assertEquals("https://addon.example/•••/manifest.json", masked)
        assertFalse(masked.contains("SECRET"))
        assertEquals("https://addon.example/manifest.json", StremioManifest.mask("https://addon.example/manifest.json"))
    }

    @Test
    fun configWithPipeKeepsItsConfig() {
        // An addon configuration ("sort=…|qualityfilter=…").
        val u = StremioManifest.normalize("https://addon.example.com/sort=seeders|qualityfilter=1080p,720p/manifest.json")!!
        assertEquals("https://addon.example.com/sort=seeders|qualityfilter=1080p,720p", u.base)
        val movie = StremioStreams.url(u.base, "tt0111161")!!
        assertEquals("https://addon.example.com/sort=seeders|qualityfilter=1080p,720p/stream/movie/tt0111161.json", movie)
        // On the wire '|' is %7C; the config segment, ',' and the series ':' stay as they are.
        assertEquals("https://addon.example.com/sort=seeders%7Cqualityfilter=1080p,720p/stream/movie/tt0111161.json", StremioManifest.httpUrl(movie).toString())
        assertEquals(
            "https://addon.example.com/sort=seeders%7Cqualityfilter=1080p,720p/stream/series/tt0903747:1:1.json",
            StremioManifest.httpUrl(StremioStreams.url(u.base, "tt0903747", 1, 1)!!).toString(),
        )
        assertEquals("https://addon.example.com/sort=seeders%7Cqualityfilter=1080p,720p/manifest.json", StremioManifest.httpUrl(u.manifest).toString())
        assertEquals(listOf("sort=seeders|qualityfilter=1080p,720p", "manifest.json"), StremioManifest.httpUrl(u.manifest).pathSegments)
    }

    @Test
    fun acceptsAddonConfigurationPaths() {
        val u = StremioManifest.normalize("stremio://addon.example.com/manifest.json")!!
        assertEquals("https://addon.example.com/manifest.json", u.manifest)
        assertEquals("https://addon.example.com", u.base)
        assertEquals("https://addon.example.com/stream/movie/tt0111161.json", StremioStreams.url(u.base, "tt0111161"))
        assertEquals("https://addon.example.com/stream/series/tt0903747:1:1.json", StremioStreams.url(u.base, "tt0903747", 1, 1))
        val m = StremioManifest.parse(
            """{"id":"org.example.streams","version":"0.0.15","name":"Example streams","description":"Provides torrent streams","catalogs":[],"resources":[{"name":"stream","types":["movie","series","anime"],"idPrefixes":["tt","kitsu"]}],"types":["movie","series","anime","other"],"behaviorHints":{"configurable":true}}""",
        ).getOrThrow()
        assertEquals("org.example.streams", m.id)
        assertEquals("Example streams", m.name)
    }

    @Test
    fun parsesAValidManifest() {
        val m = StremioManifest.parse(
            """{"id":"org.example.addon","name":"Example","version":"1.2.3","resources":[{"name":"stream","types":["movie"]}],"types":["movie","series"],"catalogs":[]}""",
        ).getOrThrow()
        assertEquals("Example", m.name)
        assertEquals("1.2.3", m.version)
        assertEquals(listOf("movie", "series"), m.types)
        assertTrue(StremioManifest.parse("""{"id":"a","name":"b","version":"1","resources":["catalog","stream"],"types":["series"]}""").isSuccess)
    }

    @Test
    fun reportsWhatIsWrong() {
        assertEquals(ManifestError.JSON, err("<html>nope</html>"))
        assertEquals(ManifestError.JSON, err("[1,2]"))
        assertEquals(ManifestError.MISSING_FIELDS, err("""{"id":"a","name":"","version":"1","resources":["stream"],"types":["movie"]}"""))
        assertEquals(ManifestError.NO_STREAM, err("""{"id":"a","name":"b","version":"1","resources":["catalog","meta"],"types":["movie"]}"""))
        assertEquals(ManifestError.NO_STREAM, err("""{"id":"a","name":"b","version":"1","types":["movie"]}"""))
        assertEquals(ManifestError.NO_TYPES, err("""{"id":"a","name":"b","version":"1","resources":["stream"],"types":["channel"]}"""))
    }
}
