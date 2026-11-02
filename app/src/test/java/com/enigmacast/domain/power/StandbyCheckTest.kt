package com.enigmacast.domain.power

import com.enigmacast.R
import com.enigmacast.playback.PreviewErrorText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StandbyCheckTest {
    @Test fun errorIsUnknownNotStandby() = runBlocking {
        assertNull(StandbyCheck.confirmed({ throw java.net.SocketTimeoutException() }, 0))
    }
    @Test fun onIsOnWithOneRead() = runBlocking {
        var n = 0
        assertEquals(false, StandbyCheck.confirmed({ n++; false }, 0)); assertEquals(1, n)
    }
    @Test fun standbyNeedsTwoReads() = runBlocking {
        val replies = ArrayDeque(listOf(true, false))
        assertEquals(false, StandbyCheck.confirmed({ replies.removeFirst() }, 0))
        assertEquals(true, StandbyCheck.confirmed({ true }, 0))
    }
    @Test fun secondReadErrorIsUnknown() = runBlocking {
        var n = 0
        assertNull(StandbyCheck.confirmed({ if (n++ == 0) true else error("auth") }, 0))
    }
    @Test fun previewNeverShowsRawCode() {
        assertEquals(R.string.preview_no_picture, PreviewErrorText.forError("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED"))
        assertEquals(R.string.preview_no_connection, PreviewErrorText.forError("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"))
        assertNull(PreviewErrorText.forError(null))
    }
}
