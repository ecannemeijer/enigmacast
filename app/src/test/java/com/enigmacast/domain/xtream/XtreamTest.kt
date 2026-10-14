package com.enigmacast.domain.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XtreamTest {
    private val acc = XtreamAccount("Test", "http://iptv.example:8080", "us er", "p&ss")

    @Test
    fun playlistsTxtSkipsCommentsAndReadsNames() {
        val txt = """
            # my playlists
            http://iptv.example:8080/get.php?username=alice&password=secret&type=m3u_plus&output=ts #Home
            #http://off.example/get.php?username=x&password=y&type=m3u_plus&output=ts
            https://other.example/get.php?username=bob&password=pw&type=m3u_plus&output=m3u8
            http://broken.example/playlist.m3u
            http://iptv.example:8080/get.php?username=alice&password=secret&type=m3u_plus&output=ts
        """.trimIndent()
        val list = XStreamityPlaylists.parseTxt(txt)
        assertEquals(2, list.size)
        assertEquals("Home", list[0].name)
        assertEquals("http://iptv.example:8080", list[0].server)
        assertEquals("alice", list[0].username)
        assertEquals("ts", list[0].output)
        assertEquals("https://other.example", list[1].server)
        assertEquals("m3u8", list[1].output)
        assertEquals("other.example", list[1].name)
    }

    @Test
    fun playlistsJsonReadsPlaylistInfo() {
        val json = """[{"playlist_info":{"name":"Box","host":"http://h.example:80","username":"u","password":"p","full_url":"http://h.example:80/get.php?username=u&password=p&type=m3u_plus&output=ts"}},
            {"playlist_info":{"name":"Two","host":"h2.example","port":"25461","username":"a","password":"b"}}]"""
        val list = XStreamityPlaylists.parseJson(json)
        assertEquals(listOf("Box", "Two"), list.map { it.name })
        assertEquals("http://h2.example:25461", list[1].server)
        assertTrue(XStreamityPlaylists.parseJson("not json").isEmpty())
    }

    @Test
    fun streamUrlsFollowTheXtreamLayout() {
        assertEquals("http://iptv.example:8080/live/us%20er/p%26ss/12.ts", XtreamUrls.live(acc, "12"))
        assertEquals("http://iptv.example:8080/live/us%20er/p%26ss/12.m3u8", XtreamUrls.live(acc.copy(output = "m3u8"), "12"))
        assertEquals("http://iptv.example:8080/movie/us%20er/p%26ss/7.mkv", XtreamUrls.movie(acc, "7", "mkv"))
        assertEquals("http://iptv.example:8080/series/us%20er/p%26ss/9.mp4", XtreamUrls.episode(acc, "9", ""))
        assertEquals("http://iptv.example:8080/player_api.php?username=us+er&password=p%26ss&action=get_live_streams&category_id=3",
            XtreamUrls.api(acc, "get_live_streams", "category_id" to "3"))
    }

    @Test
    fun loginNeverInToStringOrRedactedUrls() {
        assertFalse(acc.toString().contains("p&ss"))
        assertFalse(acc.toString().contains("us er"))
        val red = XtreamUrls.redact(XtreamUrls.live(acc, "1"), acc)
        assertFalse(red.contains("p%26ss"))
        assertFalse(red.contains("us%20er"))
    }

    @Test
    fun jsonWithMixedTypes() {
        val cats = XtreamJson.categories("""[{"category_id":"1","category_name":"News","parent_id":0},{"category_id":2,"category_name":"Sport"}]""")
        assertEquals(listOf("1", "2"), cats.map { it.id })
        val live = XtreamJson.items("""[{"num":1,"name":"NPO 1","stream_type":"live","stream_id":101,"stream_icon":"x.png","category_id":"1"}]""", XtreamSection.LIVE)
        assertEquals("101", live.single().id)
        assertEquals(1, live.single().number)
        val vod = XtreamJson.items("""[{"name":"Film","stream_id":"5","container_extension":"mkv","category_id":"3"}]""", XtreamSection.MOVIES)
        assertEquals("mkv", vod.single().extension)
        val series = XtreamJson.items("""[{"name":"Show","series_id":44,"cover":"c.jpg","category_id":"8"}]""", XtreamSection.SERIES)
        assertEquals("44", series.single().id)
        val eps = XtreamJson.episodes("""{"episodes":{"2":[{"id":"22","episode_num":1,"title":"B","container_extension":"mp4","season":2}],"1":[{"id":"11","episode_num":"3","title":"A","container_extension":"mkv","season":"1"}]}}""")
        assertEquals(listOf("11", "22"), eps.map { it.id })
    }

    @Test
    fun shortEpgTitlesAreBase64() {
        val epg = XtreamJson.shortEpg("""{"epg_listings":[{"title":"Sm91cm5hYWw=","start_timestamp":"1000","stop_timestamp":"2000"}]}""")
        assertEquals("Journaal", epg.single().title)
        assertEquals(2000L, epg.single().endSec)
    }
}

class OpenWebifFilesTest {
    @Test
    fun missingFileAnswer() {
        val path = "/etc/enigma2/xstreamity/playlists.txt"
        assertTrue(com.enigmacast.data.openwebif.OpenWebifFiles.isNotFound("File '$path' not found", path))
        assertFalse(com.enigmacast.data.openwebif.OpenWebifFiles.isNotFound("", path))
        assertFalse(com.enigmacast.data.openwebif.OpenWebifFiles.isNotFound("http://h/get.php?username=a&password=b", path))
    }
}
