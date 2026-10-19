package com.enigmacast.ui.account

import com.enigmacast.ui.theme.EnigmaType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import com.enigmacast.ui.theme.requestWhenReady
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.account.AccountSnapshot
import com.enigmacast.domain.account.PremiumFeature
import com.enigmacast.ui.theme.CompactButton
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.TvChoiceDialog
import com.enigmacast.ui.theme.TvFieldKind
import com.enigmacast.ui.theme.TvTextField
import com.enigmacast.util.asString
import java.text.DateFormat
import java.util.Date

/**
 * Create an account or log in: two mode buttons on top, then e-mail, password (and confirm when
 * creating) and the action button, top to bottom so Up/Down walk the form. [firstFocus] is the
 * first mode button.
 */
@Composable
fun AccountForm(
    model: AccountViewModel,
    firstFocus: FocusRequester,
    onSignedIn: () -> Unit = {},
    large: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ModeButton(stringResource(R.string.account_mode_create), model.creating, Modifier.focusRequester(firstFocus)) { model.switchMode(true) }
            ModeButton(stringResource(R.string.account_mode_login), !model.creating) { model.switchMode(false) }
        }
        TvTextField(
            label = stringResource(R.string.account_email),
            value = model.email,
            onValueChange = { model.email = it.take(191) },
            modifier = Modifier.fillMaxWidth(),
            large = large,
        )
        TvTextField(
            label = stringResource(R.string.account_password),
            value = model.password,
            onValueChange = { model.password = it.take(128) },
            kind = TvFieldKind.PASSWORD,
            modifier = Modifier.fillMaxWidth(),
            large = large,
        )
        if (model.creating) {
            TvTextField(
                label = stringResource(R.string.account_password_confirm),
                value = model.confirm,
                onValueChange = { model.confirm = it.take(128) },
                kind = TvFieldKind.PASSWORD,
                modifier = Modifier.fillMaxWidth(),
                large = large,
            )
        }
        model.message?.let {
            Text(it.asString(), color = Color(0xFFFFB4A8), fontSize = if (large) 17.sp else 13.sp, lineHeight = if (large) 24.sp else 18.sp)
        }
        CompactButton(
            label = stringResource(
                when {
                    model.busy -> R.string.account_busy
                    model.creating -> R.string.account_create_action
                    else -> R.string.account_login_action
                },
            ),
            onClick = { model.submit(onSignedIn) },
        )
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    CompactButton(label = if (selected) "✓ $label" else label, onClick = onClick, modifier = modifier)
}

/** "Premium until 12 Oct 2028 (trial)" / "Free". */
@Composable
fun planLine(account: AccountSnapshot, premium: Boolean): String {
    val until = account.premiumUntilMs?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }
    return when {
        premium && until != null && account.isTrial -> stringResource(R.string.account_plan_trial_until, until)
        premium && until != null -> stringResource(R.string.account_plan_premium_until, until)
        premium -> stringResource(R.string.account_plan_premium)
        account.premiumUntilMs != null && account.serverPremium -> stringResource(R.string.account_plan_expired, until.orEmpty())
        else -> stringResource(R.string.account_plan_free)
    }
}

/** Settings › Account: who is signed in, the plan and its end date, Refresh and Log out. */
@Composable
fun AccountSettingsPanel(contentFocus: FocusRequester, model: AccountViewModel = hiltViewModel()) {
    val account by model.account.collectAsState()
    val current = account
    // Signing in or out swaps the controls; if the page had the focus, put it on the new first control.
    val signedIn = current != null
    var pageHadFocus by remember { mutableStateOf(false) }
    LaunchedEffect(signedIn) {
        if (pageHadFocus) contentFocus.requestWhenReady()
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.hasFocus) pageHadFocus = true },
    ) {
        if (current == null) {
            Text(stringResource(R.string.account_signed_out), color = EnigmaColors.Muted, fontSize = 13.sp, lineHeight = 18.sp)
            AccountForm(model, contentFocus)
            return@Column
        }
        val premium = model.isPremium(current)
        Text(current.email, color = EnigmaColors.Text, fontSize = 17.sp, fontWeight = EnigmaType.Strong)
        Text(planLine(current, premium), color = if (premium) EnigmaColors.Accent else EnigmaColors.Muted, fontSize = 14.sp)
        Text(
            stringResource(R.string.account_devices, current.devicesUsed, current.maxDevices),
            color = EnigmaColors.Muted,
            fontSize = 13.sp,
        )
        if (model.offline(current)) {
            Text(stringResource(R.string.account_offline), color = EnigmaColors.Muted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        if (!premium) {
            Text(stringResource(R.string.premium_free_summary), color = EnigmaColors.Muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        model.info?.let { Text(it.asString(), color = EnigmaColors.Accent, fontSize = 13.sp) }
        model.message?.let { Text(it.asString(), color = Color(0xFFFFB4A8), fontSize = 13.sp, lineHeight = 18.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CompactButton(
                label = stringResource(if (model.busy) R.string.account_busy else R.string.account_refresh),
                onClick = model::refresh,
                modifier = Modifier.focusRequester(contentFocus),
            )
            CompactButton(label = stringResource(R.string.account_logout), onClick = model::logout)
        }
    }
}

/** The "Premium" dialog for a locked feature: what it is and how to get it. OK closes it. */
@Composable
fun PremiumDialog(feature: PremiumFeature, onDismiss: () -> Unit) {
    val name = stringResource(
        when (feature) {
            PremiumFeature.MULTIPLE_RECEIVERS -> R.string.premium_feature_receivers
            PremiumFeature.MOVIES -> R.string.premium_feature_movies
            PremiumFeature.SERIES -> R.string.premium_feature_series
            PremiumFeature.SOCCER -> R.string.premium_feature_soccer
            PremiumFeature.EPG -> R.string.premium_feature_epg
            PremiumFeature.SETTINGS_IMPORT -> R.string.premium_feature_settings_import
            PremiumFeature.XTREAM -> R.string.premium_feature_xtream
            PremiumFeature.TORRENT -> R.string.premium_feature_torrent
        },
    )
    TvChoiceDialog(
        title = stringResource(R.string.premium_title),
        message = stringResource(R.string.premium_message, name),
        choices = listOf(stringResource(R.string.ok)),
        onChoose = { onDismiss() },
        onDismiss = onDismiss,
    )
}
