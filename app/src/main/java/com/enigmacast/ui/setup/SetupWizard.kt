package com.enigmacast.ui.setup

import com.enigmacast.ui.theme.skinCanvas
import com.enigmacast.ui.theme.EnigmaType
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.enigmacast.ui.profile.AvatarImage
import com.enigmacast.ui.profile.Avatars
import com.enigmacast.ui.profile.ProfileTile
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.ToggleRow
import com.enigmacast.ui.theme.TvButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import com.enigmacast.ui.theme.TvTextField
import com.enigmacast.ui.theme.focusRequesterIf
import com.enigmacast.ui.theme.requestWhenReady

/**
 * First-run setup: one plain question per screen, the step and its name on top, Back on the left
 * and Skip / Next on the right at the bottom. Shown when no receiver is set up yet, or from
 * Settings › About. Extras (name, channel lists) are offered on Done.
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
    val skipFocus = remember { FocusRequester() }
    // Sync and channel-list steps swap their controls when loading ends or pairing succeeds: focus follows.
    val contentKey = when (step) {
        SetupStep.SYNC_LINK -> "${model.syncSearching}/${model.syncLinked}"
        SetupStep.BOUQUETS -> "${model.bouquetsLoading}"
        else -> ""
    }
    LaunchedEffect(model.stepToken, contentKey) {
        // Done's main action is Start watching; other steps focus their own first control,
        // then Next, then Skip, so the focus never gets lost while a step is still loading.
        if (model.step != SetupStep.DONE && stepFocus.requestWhenReady(attempts = 4)) return@LaunchedEffect
        if (!nextFocus.requestWhenReady(attempts = 4)) skipFocus.requestWhenReady(attempts = 4)
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
        Modifier.fillMaxSize().skinCanvas(Color(0xFF080D1C)).padding(horizontal = 56.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StepHeader(model)
        Text(stringResource(step.title()), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            stepBody(model, step),
            color = EnigmaColors.Muted,
            fontSize = 18.sp,
            lineHeight = 25.sp,
            modifier = Modifier.widthIn(max = 780.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (step) {
                    SetupStep.WELCOME -> LanguageStep(stepFocus, onChooseLanguage)
                    SetupStep.RECEIVER -> ReceiverStep(model, connection, stepFocus, onConnected = { model.next() })
                    SetupStep.SYNC_LINK -> SyncLinkStep(model, stepFocus)
                    SetupStep.IPTV_OFFER -> IptvOfferStep(model, stepFocus)
                    SetupStep.VIEWING -> ViewingStep(model, stepFocus)
                    SetupStep.ACCOUNT -> AccountStep(stepFocus, onSignedIn = { model.next() })
                    SetupStep.DONE -> DoneStep(model)
                    SetupStep.PROFILE -> ProfileStep(model, stepFocus)
                    SetupStep.BOUQUETS -> BouquetStep(model, stepFocus)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (model.hasPrevious()) TvButton(stringResource(R.string.setup_back), { model.back() })
            Spacer(Modifier.weight(1f))
            if (model.canSkip()) TvButton(stringResource(step.skipLabel()), { model.skip() }, Modifier.focusRequester(skipFocus))
            val nextLabel = when {
                step == SetupStep.WELCOME -> R.string.setup_start
                step == SetupStep.DONE -> R.string.setup_finish
                step.extra -> R.string.action_save
                else -> R.string.setup_next
            }
            if (model.canGoNext() || receiver != null && step == SetupStep.RECEIVER || signedIn != null && step == SetupStep.ACCOUNT) {
                TvButton(stringResource(nextLabel), ::next, Modifier.focusRequester(nextFocus))
            }
        }
    }
}

/** Dots for the steps on this path, then "Step 2 of 5 · Receiver" (or "Extra · Channel lists"). */
@Composable
private fun StepHeader(model: SetupViewModel) {
    val step = model.step
    val steps = model.mainSteps()
    val position = model.position()
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (position != null) {
            steps.forEachIndexed { index, _ ->
                val current = index + 1 == position.first
                Box(
                    Modifier.size(if (current) 14.dp else 10.dp).background(
                        when {
                            current -> EnigmaColors.Accent
                            index + 1 < position.first -> EnigmaColors.Text
                            else -> EnigmaColors.CardBorder
                        },
                        CircleShape,
                    ),
                )
            }
        }
        Text(
            if (position != null) {
                stringResource(R.string.setup_step_of, position.first, position.second, stringResource(step.shortName()))
            } else {
                stringResource(R.string.setup_step_extra, stringResource(step.shortName()))
            },
            color = EnigmaColors.Muted,
            fontSize = 16.sp,
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
        modifier = modifier.widthIn(max = 780.dp).fillMaxWidth(),
        headlineContent = { Text(title, fontSize = 19.sp, fontWeight = EnigmaType.Strong) },
        supportingContent = if (description.isBlank()) null else { { Text(description, fontSize = 16.sp, lineHeight = 22.sp) } },
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
        SetupNotice(stringResource(R.string.setup_receiver_ok, name.ifBlank { receiver?.name.orEmpty() }), NoticeKind.SUCCESS)
    }
    if (model.leavingReceiver) {
        Text(stringResource(R.string.setup_sync_link_searching), color = EnigmaColors.Text, fontSize = 18.sp)
    }
    WizardReceiverSetup(
        viewModel = connection,
        focus = focus,
        onConnected = onConnected,
        modifier = Modifier.widthIn(max = 780.dp),
    )
}

@Composable
private fun SyncLinkStep(model: SetupViewModel, focus: FocusRequester) {
    when {
        model.syncSearching -> Text(stringResource(R.string.setup_sync_link_searching), color = EnigmaColors.Text, fontSize = 18.sp)
        model.syncLinked -> SetupNotice(stringResource(R.string.setup_sync_link_done), NoticeKind.SUCCESS)
        else -> {
            TvButton(
                stringResource(if (model.syncLinking) R.string.setup_sync_link_pairing else R.string.setup_sync_link_pair),
                { model.linkSync() },
                Modifier.focusRequester(focus),
                enabled = !model.syncLinking,
            )
            if (model.syncFailed) SetupNotice(stringResource(R.string.setup_sync_link_failed), NoticeKind.ERROR)
            Text(stringResource(R.string.setup_sync_link_later), color = EnigmaColors.Muted, fontSize = 16.sp, lineHeight = 22.sp)
        }
    }
}

@Composable
private fun IptvOfferStep(model: SetupViewModel, focus: FocusRequester) {
    TvButton(
        stringResource(if (model.iptvBusy) R.string.setup_iptv_busy else R.string.setup_iptv_yes),
        model::takeIptv,
        Modifier.focusRequester(focus),
        enabled = !model.iptvBusy,
    )
    if (model.iptvFailed) SetupNotice(stringResource(R.string.setup_iptv_failed), NoticeKind.ERROR)
}

@Composable
private fun ProfileStep(model: SetupViewModel, focus: FocusRequester) {
    val state by model.profileState.collectAsStateWithLifecycle()
    val profiles = model.namedProfiles(state)
    LaunchedEffect(model.profilesLoading, profiles.size, model.creatingProfile) { focus.requestWhenReady() }
    if (model.profilesLoading && profiles.isEmpty()) {
        Text(stringResource(R.string.loading), color = EnigmaColors.Muted, fontSize = 17.sp, modifier = Modifier.focusRequester(focus))
        return
    }
    if (profiles.isNotEmpty() && !model.creatingProfile) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            profiles.forEachIndexed { index, profile ->
                ProfileTile(
                    profile.name,
                    profile.avatar,
                    active = false,
                    managing = false,
                    modifier = if (index == 0) Modifier.focusRequester(focus) else Modifier,
                ) { model.chooseProfile(profile.id) }
            }
            TvButton(stringResource(R.string.profile_add), { model.startNewProfile() })
        }
        return
    }
    TvTextField(
        label = stringResource(R.string.setup_profile_name),
        value = model.profileName,
        onValueChange = { model.profileName = it.take(24) },
        modifier = Modifier.widthIn(max = 520.dp).focusRequester(focus),
        large = true,
    )
    Text(stringResource(R.string.setup_profile_avatar), color = EnigmaColors.Text, fontSize = 17.sp)
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
        modifier = Modifier.focusRequesterIf(mode != StreamMode.FOLLOW_RECEIVER, focus),
    ) { model.setMode(StreamMode.OWN_TUNER) }
    BigChoice(
        title = stringResource(R.string.setup_viewing_follow),
        description = stringResource(R.string.setup_viewing_follow_desc),
        selected = mode == StreamMode.FOLLOW_RECEIVER,
        modifier = Modifier.focusRequesterIf(mode == StreamMode.FOLLOW_RECEIVER, focus),
    ) { model.setMode(StreamMode.FOLLOW_RECEIVER) }
    Text(stringResource(R.string.setup_viewing_later), color = EnigmaColors.Muted, fontSize = 16.sp)
}

@Composable
private fun BouquetStep(model: SetupViewModel, focus: FocusRequester) {
    when {
        model.bouquetsLoading -> Text(stringResource(R.string.setup_bouquets_loading), color = EnigmaColors.Text, fontSize = 17.sp)
        model.bouquetsFailed || model.bouquets.isEmpty() -> {
            SetupNotice(stringResource(R.string.setup_bouquets_failed), NoticeKind.ERROR)
            TvButton(stringResource(R.string.setup_try_again), { model.loadBouquets(force = true) }, Modifier.focusRequester(focus))
        }
        else -> {
            Text(
                stringResource(R.string.home_bouquets_count, model.selected.size, model.bouquets.size),
                color = EnigmaColors.Muted,
                fontSize = 16.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton(stringResource(R.string.home_bouquets_all), model::selectAll, Modifier.focusRequester(focus))
                TvButton(stringResource(R.string.home_bouquets_none), model::selectNone)
            }
            Column(Modifier.widthIn(max = 780.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
    val account by model.accountState.collectAsStateWithLifecycle()
    val current = account
    SetupNotice(
        if (current != null) stringResource(R.string.setup_done_account, current.email) else stringResource(R.string.setup_done_free),
        if (current != null) NoticeKind.SUCCESS else NoticeKind.INFO,
    )
    val extras = model.extras()
    if (extras.isNotEmpty()) {
        Text(stringResource(R.string.setup_done_extras), color = EnigmaColors.Text, fontSize = 17.sp, modifier = Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            extras.forEach { extra ->
                TvButton(stringResource(extra.extraLabel()), { model.openExtra(extra) }, compact = true)
            }
        }
    }
}

/** The step's explanation; Done names the receiver. */
@Composable
private fun stepBody(model: SetupViewModel, step: SetupStep): String {
    if (step != SetupStep.DONE) return stringResource(step.body())
    val name by model.receiverName.collectAsStateWithLifecycle()
    val receiver by model.receiver.collectAsStateWithLifecycle()
    return stringResource(R.string.setup_done_body, name.ifBlank { receiver?.name.orEmpty() })
}

private fun SetupStep.title(): Int = when (this) {
    SetupStep.WELCOME -> R.string.setup_welcome_title
    SetupStep.ACCOUNT -> R.string.setup_account_title
    SetupStep.RECEIVER -> R.string.setup_receiver_title
    SetupStep.SYNC_LINK -> R.string.setup_sync_link_title
    SetupStep.IPTV_OFFER -> R.string.setup_iptv_title
    SetupStep.PROFILE -> R.string.setup_profile_title
    SetupStep.VIEWING -> R.string.setup_viewing_title
    SetupStep.BOUQUETS -> R.string.setup_bouquets_title
    SetupStep.DONE -> R.string.setup_done_title
}

private fun SetupStep.body(): Int = when (this) {
    SetupStep.WELCOME -> R.string.setup_welcome_body
    SetupStep.ACCOUNT -> R.string.setup_account_body
    SetupStep.RECEIVER -> R.string.setup_receiver_body
    SetupStep.SYNC_LINK -> R.string.setup_sync_link_body
    SetupStep.IPTV_OFFER -> R.string.setup_iptv_body
    SetupStep.PROFILE -> R.string.setup_profile_body
    SetupStep.VIEWING -> R.string.setup_viewing_body
    SetupStep.BOUQUETS -> R.string.setup_bouquets_body
    SetupStep.DONE -> R.string.setup_done_body
}

/** Short name in the progress line. */
private fun SetupStep.shortName(): Int = when (this) {
    SetupStep.WELCOME -> R.string.setup_name_language
    SetupStep.RECEIVER -> R.string.setup_name_receiver
    SetupStep.SYNC_LINK -> R.string.setup_name_sync
    SetupStep.IPTV_OFFER -> R.string.setup_name_iptv
    SetupStep.VIEWING -> R.string.setup_name_viewing
    SetupStep.ACCOUNT -> R.string.setup_name_account
    SetupStep.DONE -> R.string.setup_name_done
    SetupStep.PROFILE -> R.string.setup_name_profile
    SetupStep.BOUQUETS -> R.string.setup_name_bouquets
}

private fun SetupStep.skipLabel(): Int = when (this) {
    SetupStep.SYNC_LINK -> R.string.setup_sync_link_not_now
    SetupStep.IPTV_OFFER -> R.string.setup_iptv_no
    SetupStep.ACCOUNT -> R.string.setup_skip_later
    else -> R.string.setup_skip
}

private fun SetupStep.extraLabel(): Int = when (this) {
    SetupStep.PROFILE -> R.string.setup_extra_profile
    else -> R.string.setup_extra_bouquets
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
        SetupNotice(stringResource(R.string.setup_account_signed_in, current.email), NoticeKind.SUCCESS)
        Text(
            com.enigmacast.ui.account.planLine(current, model.isPremium(current)),
            color = EnigmaColors.Accent,
            fontSize = 17.sp,
        )
        TvButton(stringResource(R.string.account_logout), model::logout, Modifier.focusRequester(focus))
    }
}
