package com.enigmacast.ui.theme

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.AndroidComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalView

/** Settings › General › Navigation sounds; provided once at the root of the app. */
val LocalNavigationSounds = compositionLocalOf { true }

/**
 * Applies [LocalNavigationSounds] to the window this is composed in. Call it once at the root
 * and once inside every `Dialog` / `Popup`: each of those is its own window with its own
 * Compose view.
 */
@Composable
fun NavigationSoundsForWindow() {
    val enabled = LocalNavigationSounds.current
    val view = LocalView.current
    SideEffect { NavigationSounds.apply(view, enabled) }
}

/**
 * Where the click on D-pad moves comes from: Compose UI plays a focus-navigation sound (and a
 * click sound) through `View.playSoundEffect` on its host view. The framework skips that call
 * when the view's `isSoundEffectsEnabled` is false, so switching it off on the Compose view and
 * its window root silences the app. Compose's global interaction-sound flag is set as well so
 * any other Compose window stays silent too. The app has no explicit `playSoundEffect` calls.
 *
 * Limit: Android plays these effects only when the system setting (Settings › Device
 * preferences › Sound › System sounds) is on. The app can mute its own sounds but cannot turn
 * the system setting off, and it cannot silence beeps that the TV or remote make by themselves.
 */
object NavigationSounds {
    @OptIn(ExperimentalComposeUiApi::class)
    fun apply(view: View, enabled: Boolean) {
        AndroidComposeUiFlags.isInteractionSoundEffectsEnabled = enabled
        view.isSoundEffectsEnabled = enabled
        (view.parent as? View)?.isSoundEffectsEnabled = enabled
        view.rootView.isSoundEffectsEnabled = enabled
    }
}
