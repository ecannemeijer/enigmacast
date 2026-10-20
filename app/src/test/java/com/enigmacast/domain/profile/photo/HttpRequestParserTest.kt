package com.enigmacast.domain.profile.photo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class HttpRequestParserTest {
    private fun stream(s: String, body: ByteArray = ByteArray(0)) = ByteArrayInputStream(s.toByteArray(Charsets.ISO_8859_1) + body)

    private fun status(block: () -> Unit): Int = try { block(); fail("no error"); 0 } catch (e: HttpError) { e.status }

    @Test fun readsGetWithQueryAndHeaders() {
        val r = HttpRequestParser.parseHead(stream("GET /upload?token=a%2Bb_c&x=0.25 HTTP/1.1\r\nHost: 192.168.1.5:8765\r\nAccept-Language: nl-NL,nl;q=0.9\r\n\r\n"))
        assertEquals("GET", r.method)
        assertEquals("/upload", r.path)
        assertEquals("a+b_c", r.query["token"])
        assertEquals("0.25", r.query["x"])
        assertEquals("nl-NL,nl;q=0.9", r.header("Accept-Language"))
    }

    @Test fun readsAPostBodyByContentLength() {
        val body = byteArrayOf(1, 2, 3, 4, 5)
        val input = stream("POST /upload?token=t HTTP/1.1\r\nContent-Length: 5\r\nContent-Type: image/jpeg\r\n\r\n", body + byteArrayOf(9, 9))
        val head = HttpRequestParser.parseHead(input)
        assertArrayEquals(body, HttpRequestParser.readBody(head, input, 10).body)
    }

    @Test fun refusesOversizedMissingOrChunkedBodies() {
        assertEquals(413, status {
            val input = stream("POST /upload HTTP/1.1\r\nContent-Length: 11000000\r\n\r\n")
            HttpRequestParser.readBody(HttpRequestParser.parseHead(input), input, PhotoImage.MAX_BYTES)
        })
        assertEquals(411, status {
            val input = stream("POST /upload HTTP/1.1\r\n\r\n")
            HttpRequestParser.readBody(HttpRequestParser.parseHead(input), input, 10)
        })
        assertEquals(411, status { HttpRequestParser.parseHead(stream("POST /upload HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n")) })
        assertEquals(400, status {
            val input = stream("POST /upload HTTP/1.1\r\nContent-Length: 8\r\n\r\n", byteArrayOf(1, 2))
            HttpRequestParser.readBody(HttpRequestParser.parseHead(input), input, 10)
        })
    }

    @Test fun refusesMalformedRequests() {
        assertEquals(400, status { HttpRequestParser.parseHead(stream("GARBAGE\r\n\r\n")) })
        assertEquals(400, status { HttpRequestParser.parseHead(stream("GET http://x/ HTTP/1.1\r\n\r\n")) })
        assertEquals(405, status { HttpRequestParser.parseHead(stream("DELETE /upload HTTP/1.1\r\n\r\n")) })
        assertEquals(400, status { HttpRequestParser.parseHead(stream("GET /upload HTTP/1.1\r\nno colon\r\n\r\n")) })
        assertEquals(400, status { HttpRequestParser.parseHead(stream("GET /upload HTTP/1.1\r\n")) })
        assertEquals(431, status { HttpRequestParser.parseHead(stream("GET / HTTP/1.1\r\nX: " + "a".repeat(20_000) + "\r\n\r\n")) })
    }

    @Test fun writesACompleteNoStoreResponse() {
        val out = ByteArrayOutputStream()
        HttpResponse.write(out, 403, "application/json", "{}".toByteArray())
        val text = out.toString("UTF-8")
        assertEquals("HTTP/1.1 403 Forbidden", text.lineSequence().first())
        listOf("Content-Length: 2", "Cache-Control: no-store", "Connection: close", "Referrer-Policy: no-referrer").forEach { assert(text.contains(it)) { it } }
        assert(text.endsWith("\r\n\r\n{}"))
    }
}
