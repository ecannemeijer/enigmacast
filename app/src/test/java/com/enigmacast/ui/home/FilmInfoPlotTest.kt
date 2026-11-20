package com.enigmacast.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class FilmInfoPlotTest {
    @Test
    fun movieUsesOverviewAndFallsBackToEventDescription() {
        assertEquals("A thief who steals secrets.", filmInfoPlot(false, "A thief who steals secrets.", ""))
        assertEquals("Guide text.", filmInfoPlot(false, "", "Guide text."))
        assertEquals("A thief who steals secrets.", filmInfoPlot(false, "A thief who steals secrets.", "Guide text."))
    }

    @Test
    fun seriesUsesEventDescriptionThenOverview() {
        assertEquals("This episode...", filmInfoPlot(true, "Show overview.", "This episode..."))
        assertEquals("Show overview.", filmInfoPlot(true, "Show overview.", ""))
        assertEquals("", filmInfoPlot(true, "", ""))
    }

    @Test
    fun blankOverviewDoesNotHideAPresentPlot() {
        // Regression: the IPTV info page must still resolve a present plot when the
        // Download action is shown (the layout keeps the same plot source).
        assertEquals("Neo wakes up.", filmInfoPlot(false, "Neo wakes up.", ""))
        assertEquals("A war among the stars.", filmInfoPlot(true, "A war among the stars.", ""))
    }
}
