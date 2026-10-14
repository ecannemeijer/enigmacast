package com.enigmacast.domain.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class IptvCategoryFilterTest {
    private val cats = listOf(XtreamCategory("1", "NL"), XtreamCategory("2", "Sport"), XtreamCategory("3", "Kids"))

    @Test fun allShownByDefault() {
        assertEquals(cats, IptvCategoryFilter.visible(cats, emptySet()))
        assertEquals(3, IptvCategoryFilter.shown(cats, emptySet()))
    }

    @Test fun hiddenAreSkippedAndFirstVisibleIsAutoLoaded() {
        val visible = IptvCategoryFilter.visible(cats, setOf("1"))
        assertEquals(listOf("2", "3"), visible.map { it.id })
        assertEquals("2", visible.first().id)
        assertEquals(2, IptvCategoryFilter.shown(cats, setOf("1")))
    }

    @Test fun newCategoriesAreVisible() {
        val more = cats + XtreamCategory("4", "Films")
        assertEquals(listOf("2", "3", "4"), IptvCategoryFilter.visible(more, setOf("1")).map { it.id })
    }

    @Test fun toggleAllNone() {
        assertEquals(setOf("2"), IptvCategoryFilter.toggle(emptySet(), "2"))
        assertEquals(emptySet<String>(), IptvCategoryFilter.toggle(setOf("2"), "2"))
        assertEquals(setOf("1", "2", "3"), IptvCategoryFilter.none(cats))
        assertEquals(0, IptvCategoryFilter.visible(cats, IptvCategoryFilter.none(cats)).size)
        assertEquals(emptySet<String>(), IptvCategoryFilter.all())
    }

    @Test fun sourcesAreSeparate() {
        assertNotEquals(IptvCategoryFilter.sourceKey("M3U", ""), IptvCategoryFilter.sourceKey("XTREAM", ""))
        assertNotEquals(IptvCategoryFilter.sourceKey(null, "abc"), IptvCategoryFilter.sourceKey("M3U", "abc"))
    }
}
