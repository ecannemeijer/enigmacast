package com.enigmacast

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.enigmacast.remote.RemoteInputBus
import com.enigmacast.ui.AppScreen
import com.enigmacast.ui.AppViewModel
import com.enigmacast.ui.connection.ConnectionScreen
import com.enigmacast.ui.dashboard.DashboardScreen
import com.enigmacast.ui.diagnostics.OsdDiagnosticsScreen
import com.enigmacast.ui.remote.EnigmaRemoteScreen
import com.enigmacast.ui.settings.SettingsScreen
import com.enigmacast.ui.theme.EnigmaCastTheme
import com.enigmacast.ui.update.AppUpdateDialog
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var remoteInputBus: RemoteInputBus
    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insets = WindowInsetsControllerCompat(window, window.decorView)
        insets.hide(WindowInsetsCompat.Type.systemBars())
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (appViewModel.updatePrompt != null) {
                    appViewModel.dismissUpdate()
                    return
                }
                when (appViewModel.screen) {
                    AppScreen.Settings, AppScreen.Diagnostics -> appViewModel.back()
                    AppScreen.Connection -> finish()
                    AppScreen.Remote -> {
                        remoteInputBus.handler?.invoke(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
                    }
                    AppScreen.Loading, AppScreen.Dashboard -> Unit
                }
            }
        })
        setContent { EnigmaCastAppContent(appViewModel) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (appViewModel.updatePrompt != null) {
            return super.dispatchKeyEvent(event)
        }
        if (appViewModel.screen == AppScreen.Remote && remoteInputBus.handler?.invoke(event) == true) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

@Composable
private fun EnigmaCastAppContent(appViewModel: AppViewModel) {
    val view = LocalView.current
    LaunchedEffect(appViewModel.screen) {
        val activity = view.context.findActivity()
        if (activity != null && appViewModel.screen != AppScreen.Connection) {
            WindowCompat.getInsetsController(activity.window, view).hide(WindowInsetsCompat.Type.ime())
        }
    }
    EnigmaCastTheme {
        Box(Modifier.fillMaxSize()) {
        when (appViewModel.screen) {
            AppScreen.Loading -> Box(Modifier.fillMaxSize().background(Color.Black))
            AppScreen.Connection -> ConnectionScreen(
                onConnected = appViewModel::openDashboard,
                onSettings = appViewModel::openSettings,
            )
            AppScreen.Dashboard -> DashboardScreen(
                showMiniPlayer = appViewModel.streamRunning,
                onStream = appViewModel::openStream,
            )
            AppScreen.Remote -> EnigmaRemoteScreen(
                onLeaveToDashboard = appViewModel::showDashboard,
            )
            AppScreen.Settings -> SettingsScreen(
                onBack = appViewModel::back,
                onEditReceiver = appViewModel::openConnection,
                onDiagnostics = appViewModel::openDiagnostics,
            )
            AppScreen.Diagnostics -> OsdDiagnosticsScreen(onBack = appViewModel::back)
        }
        appViewModel.updatePrompt?.let { prompt ->
            Box(Modifier.fillMaxSize().background(Color(0xF0101820))) {
                AppUpdateDialog(
                    release = prompt.release,
                    progress = prompt.progress,
                    message = prompt.message,
                    onUpdate = appViewModel::installUpdate,
                    onCancel = appViewModel::dismissUpdate,
                )
            }
        }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
