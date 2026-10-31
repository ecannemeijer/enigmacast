package com.enigmacast.ui.person

import androidx.compose.runtime.staticCompositionLocalOf
import com.enigmacast.domain.person.TitleTrail
import com.enigmacast.domain.person.TrailOrigin

/** The app's one way back from "where to watch" choices (see [TitleTrail]). */
object TitleTrails {
    val shared = TitleTrail()
}

/** The info page the cast row and the actor page sit on, so "where to watch" can come back to it. */
val LocalTrailOrigin = staticCompositionLocalOf<TrailOrigin?> { null }
