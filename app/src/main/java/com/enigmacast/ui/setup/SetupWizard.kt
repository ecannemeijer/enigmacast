package com.enigmacast.ui.setup

import com.enigmacast.ui.theme.EnigmaType
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.model.StreamMode
import com.enigmacast.domain.setup.SetupStep
import com.enigmacast.locale.AppLanguage
import com.enigmacast.locale.AppLanguageStore
import com.enigmacast.ui.connection.ConnectionViewModel
import com.enigmacast.ui.connection.ReceiverSetup
import com.enigmacast.ui.profile.AvatarImage
import com.enigmacast.ui.profile.Avatars
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.ToggleRow
import com.enigmacast.ui.theme.TvButton
import com.enigmacast.ui.theme.TvTextField
import com.enigmacast.ui.theme.focusRequesterIf
import com.enigmacast.ui.theme.requestWhenReady

/**
 * First-run setup: one plain question per screen, progress dots on top, Back / Skip / Next at
 * the bottom. Shown when no receiver is set up yet, or from Settings › About.
 */
@Composable
fun SetupWizard(
    onChooseLanguage: (AppLanguage) -> Unit,
    onDone: () -> Unit,
    onLeave: () -> Unit,
    model: SetupViewModel = hiltViewModel(),
    connection: ConnectionViewModel = hiltViewModel(),
) {
    val step = model.step
    val receiver by model.receiver.collectAsStateWithLifecycle()
    val signedIn by model.accountState.collectAsStateWithLifecycle()
    val stepFocus = remember { FocusRequester() }
    val nextFocus = remember { FocusRequester() }
    LaunchedEffect(model.stepToken) {
        // Done has no control of its own: its Start watching button takes the focus.
        if (model.step == SetupStep.DONE || !stepFocus.requestWhenReady(attempts = 4)) nextFocus.requestWhenReady()
    }
    BackHandler { if (!model.back()) onLeave() }
    // Decided on the model's current step at click time, never on a value captured by an
    // earlier composition: the button keeps its node across steps, and a stale "step" made
    // Done run next() (staying on Done) instead of finishing.
    fun next() {
        if (model.step == SetupStep.DONE) {
            model.finish()
            onDone()
        } else {
            model.next()
        }
    }
    Column(
        Modifier.fillMaxSize().background(Color(0xFF080D1C)).padding(horizontal = 64.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ProgressDots(step)
        Text(stringResource(step.title()), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(
            stringResource(step.body()),
            color = EnigmaColors.Muted,
            fontSize = 17.sp,
            lineHeight = 24.sp,
            modifier = Modifier.widthIn(max = 760.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (step) {
                    SetupStep.WELCOME -> LanguageStep(stepFocus, onChooseLanguage)
                    SetupStep.ACCOUNT -> AccountStep(stepFocus, onSignedIn = { model.next() })
                    SetupStep.RECEIVER -> ReceiverStep(model, connection, stepFocus, onConnected = { model.next() })
                    SetupStep.SYNC_LINK -> SyncLinkStep(model, stepFocus)
                    SetupStep.PROFILE -> ProfileStep(model, stepFocus)
                    SetupStep.VIEWING -> ViewingStep(model, stepFocus)
                    SetupStep.BOUQUETS -> BouquetStep(model, stepFocus)
                    SetupStep.SYNC -> Column(Modifier.widthIn(max = 760.dp).focusRequester(stepFocus).focusGroup()) {
                        com.enigmacast.ui.home.DataSyncSettingsCard()
                    }
                    SetupStep.SOCCER -> Column(Modifier.widthIn(max = 760.dp).focusRequester(stepFocus).focusGroup()) {
                        com.enigmacast.ui.soccer.SoccerSettingsCard()
                    }
                    SetupStep.DONE -> DoneStep(model)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (SetupStep.previous(step) != null) TvButton(stringResource(R.string.setup_back), { model.back() })
            Spacer(Modifier.weight(1f))
            if (step.optional) TvButton(stringResource(R.string.setup_skip), { model.skip() })
            val nextLabel = when (step) {
                SetupStep.WELCOME -> R.string.setup_start
                SetupStep.DONE -> R.string.setup_finish
                else -> R.string.setup_next
            }
            if (model.canGoNext() || receiver != null && step == SetupStep.RECEIVER || signedIn != null && step == SetupStep.ACCOUNT) {
                TvButton(stringResource(nextLabel), ::next, Modifier.focusRequester(nextFocus))
            }
        }
    }
}

@Composable
private fun ProgressDots(step: SetupStep) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        SetupStep.entries.forEach { s ->
            val size = if (s == step) 14.dp else 10.dp
            Box(
                Modifier.size(size).background(
                    when {
                        s == step -> EnigmaColors.Accent
                        s.ordinal < step.ordinal -> EnigmaColors.Text
                        else -> EnigmaColors.CardBorder
                    },
                    CircleShape,
                ),
            )
        }
        Text(
            stringResource(R.string.setup_step_of, step.ordinal + 1, SetupStep.entries.size),
            color = EnigmaColors.Muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** A big option: a title, a line explaining it, and a check when it is the current one. */
@Composable
private fun BigChoice(title: String, description: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ListItem(
        selected = selected,
        onClick = onClick,
        modifier = modifier.widthIn(max = 760.dp).fillMaxWidth(),
        headlineContent = { Text(title, fontSize = 19.sp, fontWeight = EnigmaType.Strong) },
        supportingContent = if (description.isBlank()) null else { { Text(description, fontSize = 14.sp, lineHeight = 19.sp) } },
        trailingContent = { if (selected) Text("✓", fontSize = 20.sp, fontWeight = FontWeight.Bold) },
        scale = ListItemDefaults.scale(scale = 1f, focusedScale = 1.02f),
    )
}

@Composable
private fun LanguageStep(focus: FocusRequester, onChoose: (AppLanguage) -> Unit) {
    val context = LocalContext.current
    val current = remember { AppLanguageStore.current(context) }
    AppLanguage.entries.forEach { language ->
        BigChoice(
            title = language.nativeName,
            description = "",
            selected = language == current,
            modifier = Modifier.focusRequesterIf(language == current, focus),
        ) { if (language != current) onChoose(language) }
    }
}

@Composable
private fun ReceiverStep(model: SetupViewModel, connection: ConnectionViewModel, focus: FocusRequester, onConnected: () -> Unit) {
    val receiver by model.receiver.collectAsStateWithLifecycle()
    val name by model.receiverName.collectAsStateWithLifecycle()
    if (receiver != null) {
        Text(
            stringResource(R.string.setup_receiver_connected, name.ifBlank { receiver?.name.orEmpty() }, receiver?.host.orEmpty()),
            color = EnigmaColors.Text,
            fontSize = 17.sp,
            fontWeight = EnigmaType.Strong,
        )
    }
    ReceiverSetup(
        viewModel = connection,
        autodetectFocus = focus,
        showConnect = true,
        onConnected = onConnected,
        modifier = Modifier.widthIn(max = 760.dp),
    )
}

@Composable
private fun SyncLinkStep(model: SetupViewModel, focus: FocusRequester) {
    if (model.syncSearching) {
        Text(stringResource(R.string.setup_sync_link_searching), color = EnigmaColors.Text, fontSize = 18.sp)
        return
    }
    val host = model.syncHost
    if (host != null) {
        Text(stringResource(R.string.setup_sync_link_found, host), color = EnigmaColors.Text, fontSize = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(stringResource(R.string.setup_sync_link_pair), { model.linkSync() }, Modifier.focusRequester(focus))
            TvButton(stringResource(R.string.setup_sync_link_alone), { model.stayStandalone() })
        }
    } else {
        Text(stringResource(R.string.setup_sync_link_missing), color = EnigmaColors.Text, fontSize = 18.sp)
        TvButton(stringResource(R.string.setup_sync_link_alone), { model.stayStandalone() }, Modifier.focusRequester(focus))
    }
    if (model.syncMessage.isNotBlank() && model.syncMessage != "missing") {
        Text(model.syncMessage, color = EnigmaColors.Muted, fontSize = 15.sp)
    }
}

@Composable
private fun ProfileStep(model: SetupViewModel, focus: FocusRequester) {
    val profiles = model.profilesForPicker()
    if (profiles.isNotEmpty()) {
        Text(stringResource(R.string.setup_sync_link_profiles), color = EnigmaColors.Text, fontSize = 16.sp)
        profiles.forEachIndexed { index, profile ->
            TvButton(
                profile.name,
                { model.chooseProfile(profile.id) },
                if (index == 0) Modifier.focusRequester(focus) else Modifier,
            )
        }
    }
    TvTextField(
        label = stringResource(R.string.setup_profile_name),
        value = model.profileName,
        onValueChange = { model.profileName = it.take(24) },
        modifier = Modifier.widthIn(max = 480.dp).then(if (profiles.isEmpty()) Modifier.focusRequester(focus) else Modifier),
        large = true,
    )
    Text(stringResource(R.string.setup_profile_avatar), color = EnigmaColors.Text, fontSize = 16.sp)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(Avatars.toList()) { index, _ ->
            val chosen = index == model.avatar
            Surface(
                onClick = { model.avatar = index },
                shape = ClickableSurfaceDefaults.shape(CircleShape),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
                colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = EnigmaColors.Accent),
                modifier = Modifier.size(84.dp),
            ) {
                Box(
                    Modifier.fillMaxSize().padding(4.dp)
                        .border(if (chosen) 3.dp else 0.dp, if (chosen) Color.White else Color.Transparent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { AvatarImage(index, 72.dp) }
            }
        }
    }
}

@Composable
private fun ViewingStep(model: SetupViewModel, focus: FocusRequester) {
    val mode by model.streamMode.collectAsStateWithLifecycle()
    BigChoice(
        title = stringResource(R.string.setup_viewing_own),
        description = stringResource(R.string.setup_viewing_own_desc),
        selected = mode == StreamMode.OWN_TUNER,
        modifier = Modifier.focusRequester(focus),
    ) { model.setMode(StreamMode.OWN_TUNER) }
    BigChoice(
        title = stringResource(R.string.setup_viewing_follow),
        description = stringResource(R.string.setup_viewing_follow_desc),
        selected = mode == StreamMode.FOLLOW_RECEIVER,
    ) { model.setMode(StreamMode.FOLLOW_RECEIVER) }
    Text(stringResource(R.string.setup_viewing_later), color = EnigmaColors.Muted, fontSize = 13.sp)
}

@Composable
private fun BouquetStep(model: SetupViewModel, focus: FocusRequester) {
    when {
        model.bouquetsLoading -> Text(stringResource(R.string.setup_bouquets_loading), color = EnigmaColors.Text, fontSize = 16.sp)
        model.bouquetsFailed || model.bouquets.isEmpty() -> {
            Text(stringResource(R.string.setup_bouquets_failed), color = EnigmaColors.Text, fontSize = 16.sp)
            TvButton(stringResource(R.string.setup_try_again), { model.loadBouquets(force = true) }, Modifier.focusRequester(focus))
        }
        else -> {
            Text(
                stringResource(R.string.home_bouquets_count, model.selected.size, model.bouquets.size),
                color = EnigmaColors.Muted,
                fontSize = 14.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton(stringResource(R.string.home_bouquets_all), model::selectAll, Modifier.focusRequester(focus))
                TvButton(stringResource(R.string.home_bouquets_none), model::selectNone)
            }
            Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                model.bouquets.forEach { bouquet ->
                    ToggleRow(
                        title = bouquet.name.ifBlank { bouquet.reference },
                        checked = bouquet.reference in model.selected,
                        onCheckedChange = { model.toggle(bouquet.reference) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DoneStep(model: SetupViewModel) {
    val name by model.receiverName.collectAsStateWithLifecycle()
    val receiver by model.receiver.collectAsStateWithLifecycle()
    Text(
        stringResource(R.string.setup_done_summary, name.ifBlank { receiver?.name.orEmpty() }, model.profileName.ifBlank { "—" }),
        color = EnigmaColors.Text,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    )
}

private fun SetupStep.title(): Int = when (this) {
    SetupStep.WELCOME -> R.string.setup_welcome_title
    SetupStep.ACCOUNT -> R.string.setup_account_title
    SetupStep.RECEIVER -> R.string.setup_receiver_title
    SetupStep.SYNC_LINK -> R.string.setup_sync_link_title
    SetupStep.PROFILE -> R.string.setup_profile_title
    SetupStep.VIEWING -> R.string.setup_viewing_title
    SetupStep.BOUQUETS -> R.string.setup_bouquets_title
    SetupStep.SYNC -> R.string.setup_sync_title
    SetupStep.SOCCER -> R.string.setup_soccer_title
    SetupStep.DONE -> R.string.setup_done_title
}

private fun SetupStep.body(): Int = when (this) {
    SetupStep.WELCOME -> R.string.setup_welcome_body
    SetupStep.ACCOUNT -> R.string.setup_account_body
    SetupStep.RECEIVER -> R.string.setup_receiver_body
    SetupStep.SYNC_LINK -> R.string.setup_sync_link_body
    SetupStep.PROFILE -> R.string.setup_profile_body
    SetupStep.VIEWING -> R.string.setup_viewing_body
    SetupStep.BOUQUETS -> R.string.setup_bouquets_body
    SetupStep.SYNC -> R.string.setup_sync_body
    SetupStep.SOCCER -> R.string.setup_soccer_body
    SetupStep.DONE -> R.string.setup_done_body
}

/** Account: the form, or who is signed in with the plan once that is done (Next goes on). */
@Composable
private fun AccountStep(focus: FocusRequester, onSignedIn: () -> Unit) {
    val model: com.enigmacast.ui.account.AccountViewModel = hiltViewModel()
    val account by model.account.collectAsStateWithLifecycle()
    val current = account
    // Log out swaps the controls: the form's first button takes the focus again.
    LaunchedEffect(current != null) { focus.requestWhenReady() }
    if (current == null) {
        com.enigmacast.ui.account.AccountForm(model, focus, onSignedIn = onSignedIn, large = true)
    } else {
        Text(
            stringResource(R.string.setup_account_signed_in, current.email),
            color = EnigmaColors.Text,
            fontSize = 17.sp,
            fontWeight = EnigmaType.Strong,
        )
        Text(
            com.enigmacast.ui.account.planLine(current, model.isPremium(current)),
            color = EnigmaColors.Accent,
            fontSize = 15.sp,
        )
        TvButton(stringResource(R.string.account_logout), model::logout, Modifier.focusRequester(focus))
    }
}
