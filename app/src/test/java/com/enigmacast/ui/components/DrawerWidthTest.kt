package com.enigmacast.ui.components

import androidx.compose.ui.unit.dp
import com.enigmacast.ui.theme.SkinNav
import com.enigmacast.ui.theme.Skins
import org.junit.Assert.assertEquals
import org.junit.Test

class DrawerWidthTest {
    @Test
    fun expandedRailIsFifteenPercentNarrowerThanTheOriginalTwoHundredDp() {
        assertEquals(72.dp, DrawerWidth.Collapsed)
        assertEquals(170.dp, DrawerWidth.Expanded)
    }

    @Test
    fun navChromeLeavesRoomForTheLongestLabels() {
        for (skin in Skins.all) {
            assertEquals(6.dp, SkinNav.of(skin).horizontalPadding)
        }
    }
}
