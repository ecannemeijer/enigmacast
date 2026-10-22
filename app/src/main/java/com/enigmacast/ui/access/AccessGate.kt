package com.enigmacast.ui.access

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.enigmacast.ui.account.AccountForm
import com.enigmacast.ui.account.AccountViewModel
import com.enigmacast.ui.components.EnigmaCastBootLogo
import com.enigmacast.ui.theme.CompactButton
import com.enigmacast.ui.theme.EnigmaCastTheme
import com.enigmacast.ui.theme.EnigmaColors

/**
 * The one gate in front of the app. Loading decides Free/Premium, Premium asks for a login and a
 * too-old client gets the update screen. [onBlocked] lets the activity stop playback while the
 * gate is up, so a login screen never plays audio from the stream underneath.
 */
@Composable
fun AccessGate(
    model: AccessViewModel,
    onBlocked: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val blocked = state.loading || state.needsLogin || state.updateRequired
    LaunchedEffect(blocked) { onBlocked(blocked) }
    EnigmaCastTheme {
        when {
            state.loading -> Box(Modifier.fillMaxSize().background(EnigmaColors.Canvas), contentAlignment = Alignment.Center) {
                EnigmaCastBootLogo()
            }
            state.updateRequired -> UpdateRequiredScreen(state)
            state.needsLogin -> LoginGate(state)
            else -> content()
        }
    }
}

@Composable
private fun LoginGate(state: AccessUiState) {
    val accountModel: AccountViewModel = hiltViewModel()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(EnigmaColors.Canvas).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 620.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "EnigmaCast Premium",
                color = EnigmaColors.Text,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                state.message.ifBlank { "Log in om EnigmaCast te gebruiken." },
                color = EnigmaColors.Muted,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            AccountForm(accountModel, focus, large = true, modifier = Modifier.focusRequester(focus))
        }
    }
}

@Composable
private fun UpdateRequiredScreen(state: AccessUiState) {
    val context = LocalContext.current
    Box(
        Modifier.fillMaxSize().background(EnigmaColors.Canvas).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 620.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("EnigmaCast bijwerken", color = EnigmaColors.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                if (state.minVersionName.isBlank()) {
                    "Deze versie wordt niet meer ondersteund. Werk EnigmaCast bij om verder te gaan."
                } else {
                    "Deze versie wordt niet meer ondersteund. Werk bij naar ${state.minVersionName} om verder te gaan."
                },
                color = EnigmaColors.Muted,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
            CompactButton(
                label = "Update downloaden",
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.updateUrl)))
                    }
                },
            )
            Text(state.updateUrl, color = EnigmaColors.Subtle, fontSize = 11.sp, textAlign = TextAlign.Center)
        }
    }
}

