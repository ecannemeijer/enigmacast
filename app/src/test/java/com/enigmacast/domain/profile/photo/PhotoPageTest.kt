package com.enigmacast.domain.profile.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoPageTest {
    @Test fun picksThePhonesLanguage() {
        assertEquals("nl", PhotoPage.language("nl-NL,nl;q=0.9,en-US;q=0.8"))
        assertEquals("de", PhotoPage.language("es-ES,de;q=0.7,fr;q=0.5"))
        assertEquals("fr", PhotoPage.language("it;q=0.2, fr-BE;q=0.9"))
        assertEquals("en", PhotoPage.language("ja-JP"))
        assertEquals("en", PhotoPage.language(null))
        assertEquals("en", PhotoPage.language("nl;q=0"))
    }

    @Test fun pageIsLocalisedEscapedAndSelfContained() {
        val html = PhotoPage.page("nl", "<Bob & \"Ann\">")
        assertTrue(html.contains("Profielfoto instellen"))
        assertTrue(html.contains("Kies foto uit galerij"))
        assertTrue(html.contains("Opslaan"))
        assertTrue(html.contains("accept=\"image/*\""))
        assertTrue(html.contains("&lt;Bob &amp; &quot;Ann&quot;&gt;"))
        assertFalse(html.contains("<Bob"))
        // No CDN or other outside resources.
        assertFalse(Regex("""(src|href)\s*=\s*["']?https?:""").containsMatchIn(html))
        assertFalse(html.contains("cdn", ignoreCase = true))
    }

    @Test fun everyLanguageHasEveryText() {
        val keys = listOf("title", "pick", "save", "done", "errToken", "errSize", "errImage", "errNet")
        for (lang in PhotoPage.LANGUAGES) for (k in keys) {
            assertTrue("$lang/$k", PhotoPage.text(lang, k).isNotBlank())
            if (lang != "en" && k != "zoom") assertFalse("$lang/$k untranslated", PhotoPage.text(lang, k) == PhotoPage.text("en", k))
        }
    }
}
