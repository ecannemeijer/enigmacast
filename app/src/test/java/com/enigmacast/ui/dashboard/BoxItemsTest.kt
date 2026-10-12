package com.enigmacast.ui.dashboard

import com.enigmacast.data.openwebif.OpenWebifJson
import com.enigmacast.domain.recording.Recording
import com.enigmacast.domain.recording.RecordingPlace
import com.enigmacast.domain.recording.RecordingStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class BoxItemsTest {
    private fun r(id: String) = Recording(id, id, "", "", -1, 0, 0, RecordingPlace.RECEIVER, RecordingStatus.READY, "")

    @Test
    fun movieLengths() {
        assertEquals(5412L, OpenWebifJson.movieLength("90:12"))
        assertEquals(5412L, OpenWebifJson.movieLength("1:30:12"))
        assertEquals(0L, OpenWebifJson.movieLength("?"))
        assertEquals(0L, OpenWebifJson.movieLength(null))
    }

    @Test
    fun listIndexCountsHeaders() {
        val app = listOf(r("a1"), r("a2"))
        val movies = listOf(r("m1"))
        val timers = listOf(r("t1"))
        assertEquals(1, listIndexOf("a1", app, movies, timers))
        assertEquals(4, listIndexOf("m1", app, movies, timers))
        assertEquals(6, listIndexOf("t1", app, movies, timers))
        assertEquals(1, listIndexOf("a2", app, emptyList(), emptyList()))
    }
}
