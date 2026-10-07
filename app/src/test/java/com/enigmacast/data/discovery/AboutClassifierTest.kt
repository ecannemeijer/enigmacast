package com.enigmacast.data.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutClassifierTest {
    @Test
    fun anSf8008AboutAnswerIsAReceiverWithBrandModelAndImage() {
        val verdict = AboutClassifier.classify(200, SF8008_ABOUT)
        assertEquals(AboutVerdict.Receiver(model = "Octagon SF8008 4K", image = "openATV 7.4", loginRequired = false), verdict)
    }

    @Test
    fun keysAtTheTopLevelAndOnlyAWebifVersionAlsoCount() {
        assertTrue(AboutClassifier.classify(200, """{"model":"Vu+ Duo 4K","webifver":"OWIF 2.2"}""") is AboutVerdict.Receiver)
        assertEquals(
            AboutVerdict.Receiver(model = "", image = "", loginRequired = false),
            AboutClassifier.classify(200, """{"info":{"webifver":"OWIF 1.4"}}"""),
        )
        assertEquals("DM900", (AboutClassifier.classify(200, """{"info":{"boxtype":"dm900"}}""") as AboutVerdict.Receiver).model)
        assertTrue(AboutClassifier.classify(200, """{"info":{"brand":"Dreambox"}}""") is AboutVerdict.Receiver)
    }

    @Test
    fun aLoginPromptIsAReceiverThatNeedsCredentials() {
        assertEquals(AboutVerdict.Receiver(model = "", image = "", loginRequired = true), AboutClassifier.classify(401, ""))
    }

    @Test
    fun otherWebDevicesAreNotReceivers() {
        assertEquals(AboutVerdict.NotEnigma2, AboutClassifier.classify(200, "<html><body>Router login</body></html>"))
        assertEquals(AboutVerdict.NotEnigma2, AboutClassifier.classify(200, """{"status":"ok","version":"1.2"}"""))
        assertEquals(AboutVerdict.NotEnigma2, AboutClassifier.classify(200, """{"brand":"HP"}"""))
        assertEquals(AboutVerdict.NotEnigma2, AboutClassifier.classify(404, SF8008_ABOUT))
        assertEquals(AboutVerdict.NotEnigma2, AboutClassifier.classify(200, ""))
        assertEquals(AboutVerdict.NotEnigma2, AboutClassifier.classify(200, "[1,2,3]"))
    }
}
