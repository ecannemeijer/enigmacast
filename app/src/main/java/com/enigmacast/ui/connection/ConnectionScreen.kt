package com.enigmacast.ui.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.data.network.TunnelState
import com.enigmacast.domain.network.WireGuardIssue
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.requestWhenReady

@Composable
fun ConnectionScreen(
    onConnected: () -> Unit,
    onSettings: () -> Unit,
    viewModel: ConnectionViewModel = hiltViewModel(),
) {
    val autodetectFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { autodetectFocus.requestWhenReady() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("EnigmaCast", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(
            stringResource(R.string.conn_intro),
            color = EnigmaColors.Muted,
            fontSize = 15.sp,
            modifier = Modifier.widthIn(max = 640.dp),
        )
        ReceiverSetup(
            viewModel = viewModel,
            autodetectFocus = autodetectFocus,
            showConnect = true,
            onConnected = onConnected,
            onSettings = onSettings,
            modifier = Modifier.widthIn(max = 720.dp),
        )
    }
}

internal fun WireGuardIssue.label(): Int = when (this) {
    WireGuardIssue.EMPTY -> R.string.wg_issue_empty
    WireGuardIssue.NO_INTERFACE -> R.string.wg_issue_no_interface
    WireGuardIssue.NO_PRIVATE_KEY -> R.string.wg_issue_no_private_key
    WireGuardIssue.NO_ADDRESS -> R.string.wg_issue_no_address
    WireGuardIssue.NO_PEER -> R.string.wg_issue_no_peer
    WireGuardIssue.NO_PUBLIC_KEY -> R.string.wg_issue_no_public_key
    WireGuardIssue.NO_ENDPOINT -> R.string.wg_issue_no_endpoint
    WireGuardIssue.NO_ALLOWED_IPS -> R.string.wg_issue_no_allowed_ips
}

internal fun TunnelState.label(): Int = when (this) {
    TunnelState.OFF -> R.string.wg_status_off
    TunnelState.CONNECTING -> R.string.wg_status_connecting
    TunnelState.UP -> R.string.wg_status_up
    TunnelState.NEEDS_CONSENT -> R.string.wg_status_consent
    TunnelState.FAILED -> R.string.wg_status_failed
}

/** Shows private and preshared keys as dots; same length, so cursor positions stay the same. */
internal object HideWireGuardKeys : VisualTransformation {
    private val keyLine = Regex("(?im)^(\\s*(?:PrivateKey|PresharedKey)\\s*=\\s*)(\\S+)")

    override fun filter(text: AnnotatedString): TransformedText {
        val masked = keyLine.replace(text.text) { match -> match.groupValues[1] + "•".repeat(match.groupValues[2].length) }
        return TransformedText(AnnotatedString(masked), OffsetMapping.Identity)
    }
}
