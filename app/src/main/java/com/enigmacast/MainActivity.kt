package com.enigmacast

import android.app.Activity
import android.app.SearchManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.enigmacast.locale.AppLanguage
import com.enigmacast.locale.AppLanguageStore
import com.enigmacast.remote.RemoteInputBus
import com.enigmacast.ui.AppScreen
import com.enigmacast.ui.AppViewModel
import com.enigmacast.ui.animation.ProvideEnigmaMotion
import com.enigmacast.ui.connection.ConnectionScreen
import com.enigmacast.ui.dashboard.DashboardScreen
import com.enigmacast.ui.diagnostics.OsdDiagnosticsScreen
import com.enigmacast.ui.reminder.AutoSwitchMessage
import com.enigmacast.ui.reminder.AutoSwitchNotice
import com.enigmacast.ui.remote.EnigmaRemoteScreen
import com.enigmacast.ui.settings.SettingsScreen
import com.enigmacast.ui.theme.EnigmaCastTheme
import com.enigmacast.ui.theme.TvConfirmDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.enigmacast.ui.theme.LocalNavigationSounds
import com.enigmacast.ui.theme.NavigationSoundsForWindow
import com.enigmacast.ui.update.AppUpdateDialog
import com.enigmacast.util.asString
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var remoteInputBus: RemoteInputBus
    private val appViewModel: AppViewModel by viewModels()
    private var reminderKeyDown = NO_KEY
    private var backHoldJob: Job? = null
    private var backHoldOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insets = WindowInsetsControllerCompat(window, window.decorView)
        insets.hide(WindowInsetsCompat.Type.systemBars())
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        appViewModel.requestFinish = { finish() }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (appViewModel.exitPrompt) {
                    appViewModel.dismissExit()
                    return
                }
                if (appViewModel.updatePrompt != null) {
                    appViewModel.dismissUpdate()
                    return
                }
                when (appViewModel.screen) {
                    AppScreen.Settings, AppScreen.Diagnostics, AppScreen.SignalMeter, AppScreen.Oscam -> appViewModel.back()
                    AppScreen.Connection -> appViewModel.askExit()
                    AppScreen.Remote -> {
                        remoteInputBus.handler?.invoke(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
                    }
                    AppScreen.Loading -> Unit
                    AppScreen.Dashboard -> {
                        if (appViewModel.onDashboardBack?.invoke() != true) appViewModel.askExit()
                    }
                }
            }
        })
        setContent { EnigmaCastAppContent(appViewModel, ::chooseLanguage) }
        handleSearchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSearchIntent(intent)
    }

    /** Assistant and the TV search orb send SEARCH or MEDIA_PLAY_FROM_SEARCH with a query extra. */
    private fun handleSearchIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_SEARCH && action != MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return
        val query = intent.getStringExtra(SearchManager.QUERY)
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: return
        appViewModel.openAssistantSearch(query, playFirst = action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
    }

    override fun onStart() {
        super.onStart()
        appViewModel.setForeground(true)
    }

    override fun onStop() {
        appViewModel.setForeground(false)
        super.onStop()
    }

    /**
     * While the AutoSwitch countdown shows, OK switches now and Back cancels it; every other key
     * (and the focus) stays with the screen. A key whose press went to the notice also gets its
     * release, so the screen behind never sees half a key press.
     */
    private fun handleReminderKey(event: KeyEvent): Boolean {
        val switch = event.keyCode in REMINDER_SWITCH_KEYS
        if (!switch && event.keyCode !in REMINDER_CANCEL_KEYS) return false
        val shown = appViewModel.reminderNotice.value != null
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (!shown && reminderKeyDown != event.keyCode) return false
                reminderKeyDown = event.keyCode
                true
            }
            KeyEvent.ACTION_UP -> {
                if (reminderKeyDown != event.keyCode) return false
                reminderKeyDown = NO_KEY
                if (shown) {
                    if (switch) appViewModel.switchToReminderNow() else appViewModel.cancelReminderNotice()
                }
                true
            }
            else -> false
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageStore.wrap(newBase))
    }

    /** Applies a new app language at once; the recreated screen returns to Settings › Language. */
    private fun chooseLanguage(language: AppLanguage) {
        if (language == AppLanguageStore.current(this)) return
        appViewModel.requestLanguageFocus()
        if (!AppLanguageStore.choose(this, language)) recreate()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (appViewModel.updatePrompt != null) {
            return super.dispatchKeyEvent(event)
        }
        if (handleReminderKey(event)) return true
        // Long-press Back (held BACK_HOLD_MS) from any menu returns to the full-screen player while a
        // stream runs. The first key-down goes through as always, so a short Back behaves exactly as
        // before (Back acts on key-up). Once the hold fires, the repeats and the key-up are swallowed,
        // so no normal Back follows. Activity.onKeyDown always "consumes" Back (it starts tracking),
        // so the hold is armed regardless of what the down returned.
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            when {
                event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 -> {
                    backHoldJob?.cancel()
                    backHoldOpened = false
                    if (canHoldBackToStream()) {
                        backHoldJob = lifecycleScope.launch {
                            delay(BACK_HOLD_MS)
                            if (canHoldBackToStream()) {
                                backHoldOpened = true
                                appViewModel.openStream()
                            }
                        }
                    }
                }
                event.action == KeyEvent.ACTION_DOWN && (backHoldJob?.isActive == true || backHoldOpened) -> return true
                event.action == KeyEvent.ACTION_UP -> {
                    backHoldJob?.cancel()
                    backHoldJob = null
                    if (backHoldOpened) {
                        backHoldOpened = false
                        return true
                    }
                }
            }
        }
        // Play/Pause on a menu (not in the player) also returns to the full-screen player.
        if (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && canHoldBackToStream()) {
            if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) appViewModel.openStream()
            return true
        }
        if (appViewModel.screen == AppScreen.Remote && remoteInputBus.handler?.invoke(event) == true) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun canHoldBackToStream(): Boolean {
        if (!appViewModel.streamRunning || appViewModel.exitPrompt || appViewModel.updatePrompt != null) return false
        return when (appViewModel.screen) {
            AppScreen.Remote, AppScreen.Loading, AppScreen.Connection -> false
            else -> true
        }
    }
}

@Composable
private fun EnigmaCastAppContent(appViewModel: AppViewModel, onChooseLanguage: (AppLanguage) -> Unit) {
    val navigationSounds by appViewModel.navigationSounds.collectAsState()
    val motionQuality by appViewModel.motionQuality.collectAsState()
    CompositionLocalProvider(LocalNavigationSounds provides navigationSounds) {
        ProvideEnigmaMotion(motionQuality) {
            NavigationSoundsForWindow()
            EnigmaCastAppBody(appViewModel, onChooseLanguage)
        }
    }
}

@Composable
private fun EnigmaCastAppBody(appViewModel: AppViewModel, onChooseLanguage: (AppLanguage) -> Unit) {
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
            AppScreen.Loading -> com.enigmacast.ui.components.EnigmaCastBootLogo()
            AppScreen.Connection -> ConnectionScreen(
                onConnected = appViewModel::openDashboard,
                onSettings = appViewModel::openSettings,
            )
            AppScreen.Dashboard -> DashboardScreen(
                showMiniPlayer = appViewModel.streamRunning,
                onStream = appViewModel::openStream,
                onMiniPlayer = appViewModel::showMiniPlayer,
                onExit = appViewModel::exit,
                onBindBack = { appViewModel.onDashboardBack = it },
                focusPlayingChannel = appViewModel.focusPlayingChannel,
                onPlayingChannelFocused = appViewModel::playingChannelFocused,
                returnToMyItemsId = appViewModel.returnToMyItemsId,
                onMyItemsFocused = appViewModel::myItemsFocused,
                updatePromptShown = appViewModel.updatePrompt != null,
                focusLanguageSetting = appViewModel.focusLanguageSetting,
                onLanguageSettingFocused = appViewModel::languageSettingFocused,
                returnToSettingsHub = appViewModel.returnToSettingsHub,
                onSettingsHubShown = appViewModel::settingsHubShown,
                onSignalMeter = appViewModel::openSignalMeter,
                onOscam = appViewModel::openOscam,
                onChooseLanguage = onChooseLanguage,
                pendingSearchQuery = appViewModel.pendingSearchQuery,
                pendingSearchPlay = appViewModel.pendingSearchPlay,
                pendingSearchToken = appViewModel.pendingSearchToken,
                onAssistantSearchConsumed = appViewModel::assistantSearchConsumed,
            )
            AppScreen.Remote -> EnigmaRemoteScreen(
                onLeaveToDashboard = appViewModel::showDashboard,
                onLeaveToMyItems = appViewModel::showDashboardFromRecording,
                pendingSearchQuery = appViewModel.pendingSearchQuery,
                pendingSearchPlay = appViewModel.pendingSearchPlay,
                pendingSearchToken = appViewModel.pendingSearchToken,
                onAssistantSearchConsumed = appViewModel::assistantSearchConsumed,
            )
            AppScreen.Settings -> SettingsScreen(
                onBack = appViewModel::back,
                onEditReceiver = appViewModel::openConnection,
                onDiagnostics = appViewModel::openDiagnostics,
                onSignalMeter = appViewModel::openSignalMeter,
                onOscam = appViewModel::openOscam,
            )
            AppScreen.Diagnostics -> OsdDiagnosticsScreen(onBack = appViewModel::back)
            AppScreen.SignalMeter -> com.enigmacast.ui.settings.SignalMeterScreen(onBack = appViewModel::back)
            AppScreen.Oscam -> com.enigmacast.ui.settings.OscamScreen(onBack = appViewModel::back)
        }
        val reminderNotice by appViewModel.reminderNotice.collectAsState()
        Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 28.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            reminderNotice?.let { AutoSwitchNotice(it) }
            appViewModel.reminderMessage?.let { AutoSwitchMessage(it.asString()) }
        }
        if (appViewModel.exitPrompt) {
            TvConfirmDialog(
                title = stringResource(R.string.exit_app_title),
                message = stringResource(R.string.exit_app_message),
                confirmLabel = stringResource(R.string.exit_app_confirm),
                dismissLabel = stringResource(R.string.cancel),
                onConfirm = appViewModel::confirmExit,
                onDismiss = appViewModel::dismissExit,
                destructive = true,
            )
        }
        appViewModel.updatePrompt?.let { prompt ->
            // Its own window: the screen behind keeps its focus requests to itself.
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

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private const val NO_KEY = -1
private const val BACK_HOLD_MS = 650L
private val REMINDER_SWITCH_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
private val REMINDER_CANCEL_KEYS = setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE)
