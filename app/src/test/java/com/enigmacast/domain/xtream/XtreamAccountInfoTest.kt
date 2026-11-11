package com.enigmacast.domain.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XtreamAccountInfoTest {
    private val now = 1_700_000_000L

    @Test
    fun readsTypicalPlayerApiUserInfo() {
        val body = """
            {"user_info":{"username":"demo","status":"Active","exp_date":"1701721600",
            "is_trial":"0","active_cons":"1","created_at":"1600000000","max_connections":"3"},
            "server_info":{"url":"x"}}
        """.trimIndent()
        val info = XtreamAccountInfo.parse(body, fetchedAtMs = 10)!!
        assertEquals("Active", info.status)
        assertEquals(1_701_721_600L, info.expDateEpochSec)
        assertEquals(1_600_000_000L, info.createdAtEpochSec)
        assertEquals(1, info.activeCons)
        assertEquals(3, info.maxConnections)
        assertFalse(info.isTrial)
        assertEquals(10L, info.fetchedAtMs)
    }

    @Test
    fun missingAndNullFieldsAreSafe() {
        val body = """{"user_info":{"status":"Active","exp_date":"null","active_cons":null,"is_trial":"1"}}"""
        val info = XtreamAccountInfo.parse(body)!!
        assertEquals("Active", info.status)
        assertNull(info.expDateEpochSec)
        assertNull(info.activeCons)
        assertNull(info.maxConnections)
        assertTrue(info.isTrial)
    }

    @Test
    fun numericFieldsMayBeNumbers() {
        val body = """{"user_info":{"status":"Disabled","exp_date":1701721600,"is_trial":1,"active_cons":2,"max_connections":4,"created_at":1600000000}}"""
        val info = XtreamAccountInfo.parse(body)!!
        assertEquals("Disabled", info.status)
        assertEquals(1_701_721_600L, info.expDateEpochSec)
        assertEquals(2, info.activeCons)
        assertEquals(4, info.maxConnections)
        assertTrue(info.isTrial)
    }

    @Test
    fun emptyOrM3uBodyIsIgnored() {
        assertNull(XtreamAccountInfo.parse(""))
        assertNull(XtreamAccountInfo.parse("<html>not json</html>"))
        assertNull(XtreamAccountInfo.parse("""{"server_info":{"url":"x"}}"""))
    }

    @Test
    fun daysLeftAndWarningWindow() {
        val info = XtreamAccountInfo(expDateEpochSec = now + 3 * 86_400L)
        assertEquals(3L, info.daysLeft(now))
        assertTrue(info.needsWarning(now, 7))
        assertFalse(info.needsWarning(now, 2))
        assertFalse(info.needsWarning(now, 0))
        val expired = XtreamAccountInfo(expDateEpochSec = now - 10)
        assertTrue(expired.isExpired(now))
        assertTrue(expired.needsWarning(now, 7))
        val unlimited = XtreamAccountInfo(status = "Active")
        assertNull(unlimited.daysLeft(now))
        assertFalse(unlimited.needsWarning(now, 7))
    }

    @Test
    fun zeroExpDateMeansUnknown() {
        assertNull(XtreamAccountInfo.parse("""{"user_info":{"exp_date":"0","status":"Active"}}""")!!.expDateEpochSec)
    }
}
