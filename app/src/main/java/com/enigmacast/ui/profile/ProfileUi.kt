package com.enigmacast.ui.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.profile.Profile
import com.enigmacast.domain.profile.ProfileState
import com.enigmacast.ui.soccer.focusLook
import com.enigmacast.ui.soccer.rememberTvFocus
import com.enigmacast.ui.soccer.tvClick
import com.enigmacast.ui.soccer.trapFocus
import com.enigmacast.ui.theme.DialogButton
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.TvChoiceDialog
import com.enigmacast.ui.theme.TvTextField
import com.enigmacast.ui.theme.focusRequesterIf
import com.enigmacast.ui.theme.requestWhenReady

/** Bundled avatars (DiceBear, CC0: Lorelei, Notionists, Pixel Art, Thumbs). */
internal val Avatars = intArrayOf(
    R.drawable.avatar_01, R.drawable.avatar_02, R.drawable.avatar_03, R.drawable.avatar_04,
    R.drawable.avatar_05, R.drawable.avatar_06, R.drawable.avatar_07, R.drawable.avatar_08,
    R.drawable.avatar_09, R.drawable.avatar_10, R.drawable.avatar_11, R.drawable.avatar_12,
    R.drawable.avatar_13, R.drawable.avatar_14, R.drawable.avatar_15, R.drawable.avatar_16,
)

@Composable
fun AvatarImage(index: Int, size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(Avatars[Math.floorMod(index, Avatars.size)]),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(RoundedCornerShape(size / 5)),
    )
}

/** "Who's watching?": one tile per profile and Add profile; Manage switches OK to editing. */
@Composable
fun ProfilePicker(model: ProfileViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Profile?>(null) }
    var adding by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = model::closePicker, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true)) {
        LaunchedEffect(Unit) { first.requestWhenReady() }
        Column(
            Modifier.fillMaxSize().background(EnigmaColors.Canvas).padding(48.dp).trapFocus(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                stringResource(if (model.managing) R.string.profile_manage else R.string.profile_who),
                color = EnigmaColors.Text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
            )
            Row(Modifier.padding(top = 32.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                state.profiles.forEachIndexed { i, p ->
                    ProfileTile(p.name, p.avatar, p.id == state.activeId, model.managing, Modifier.focusRequesterIf(i == 0, first)) {
                        if (model.managing) editing = p else model.choose(p.id)
                    }
                }
                if (state.profiles.size < MAX_PROFILES) AddTile { adding = true }
            }
            Row(Modifier.padding(top = 32.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DialogButton(
                    stringResource(if (model.managing) R.string.profile_done else R.string.profile_manage),
                    model::toggleManage, destructive = false, enabled = true, compact = true,
                )
            }
        }
    }
    editing?.let { p ->
        ProfileEditor(
            title = stringResource(R.string.profile_edit),
            initialName = p.name, initialAvatar = p.avatar,
            canDelete = model.canDelete(p.id),
            onSave = { name, avatar -> model.edit(p.id, name, avatar); editing = null },
            onDelete = { model.delete(p.id); editing = null },
            onDismiss = { editing = null },
        )
    }
    if (adding) {
        ProfileEditor(
            title = stringResource(R.string.profile_add),
            initialName = "", initialAvatar = state.profiles.size % ProfileState.AVATAR_COUNT,
            canDelete = false,
            onSave = { name, avatar -> model.add(name, avatar); adding = false },
            onDelete = {},
            onDismiss = { adding = false },
        )
    }
}

private const val MAX_PROFILES = 6

@Composable
private fun ProfileTile(name: String, avatar: Int, active: Boolean, managing: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val focus = rememberTvFocus()
    Column(
        modifier.width(132.dp).tvClick(focus, onClick).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
            AvatarImage(
                avatar, 120.dp,
                Modifier.border(if (focus.focused) 4.dp else 0.dp, if (focus.focused) Color.White else Color.Transparent, RoundedCornerShape(24.dp)),
            )
            if (managing) {
                Text("✎", color = Color.White, fontSize = 18.sp, modifier = Modifier.align(Alignment.Center).background(Color(0x99000000), CircleShape).padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        Text(
            name, color = if (focus.focused || active) EnigmaColors.Text else EnigmaColors.Muted,
            fontSize = 15.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AddTile(onClick: () -> Unit) {
    val focus = rememberTvFocus()
    Column(
        Modifier.width(132.dp).tvClick(focus, onClick).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(120.dp).clip(RoundedCornerShape(24.dp)).background(EnigmaColors.Raised)
                .border(if (focus.focused) 4.dp else 1.dp, if (focus.focused) Color.White else EnigmaColors.CardBorder, RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center,
        ) { Text("+", color = EnigmaColors.Text, fontSize = 48.sp) }
        Text(stringResource(R.string.profile_add), color = EnigmaColors.Muted, fontSize = 15.sp)
    }
}

/** Name, avatar grid (8 × 2), then Save / Delete / Cancel. Compact like the other dialogs. */
@Composable
fun ProfileEditor(
    title: String,
    initialName: String,
    initialAvatar: Int,
    canDelete: Boolean,
    onSave: (String, Int) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var avatar by remember { mutableIntStateOf(initialAvatar) }
    var confirmDelete by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    if (confirmDelete) {
        TvChoiceDialog(
            title = stringResource(R.string.profile_delete_title, name),
            message = stringResource(R.string.profile_delete_message),
            choices = listOf(stringResource(R.string.profile_delete), stringResource(R.string.cancel)),
            stacked = true,
            onChoose = { i -> confirmDelete = false; if (i == 0) onDelete() },
            onDismiss = { confirmDelete = false },
        )
        return
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(Unit) { field.requestWhenReady() }
        Column(
            Modifier.widthIn(max = 560.dp)
                .background(EnigmaColors.Selected, RoundedCornerShape(10.dp))
                .border(1.dp, EnigmaColors.CardBorder, RoundedCornerShape(10.dp))
                .padding(14.dp)
                .trapFocus(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, color = EnigmaColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            TvTextField(stringResource(R.string.profile_name), name, { name = it.take(20) }, fieldFocus = field)
            Text(stringResource(R.string.profile_avatar), color = EnigmaColors.Muted, fontSize = 12.sp)
            for (row in 0 until 2) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (col in 0 until 8) {
                        val i = row * 8 + col
                        val focus = rememberTvFocus()
                        Box(
                            Modifier.tvClick(focus) { avatar = i }
                                .border(
                                    if (focus.focused || avatar == i) 3.dp else 0.dp,
                                    when { focus.focused -> Color.White; avatar == i -> EnigmaColors.Cyan; else -> Color.Transparent },
                                    RoundedCornerShape(12.dp),
                                ).padding(3.dp),
                        ) { AvatarImage(i, 52.dp) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(stringResource(R.string.profile_save), { if (name.isNotBlank()) onSave(name, avatar) }, destructive = false, enabled = name.isNotBlank(), compact = true)
                if (canDelete) DialogButton(stringResource(R.string.profile_delete), { confirmDelete = true }, destructive = true, enabled = true, compact = true)
                DialogButton(stringResource(R.string.cancel), onDismiss, destructive = false, enabled = true, compact = true)
            }
        }
    }
}

/** The active profile in the left menu: avatar (and name when the menu is open). OK opens [ProfileMenu]. */
@Composable
fun ProfileChip(model: ProfileViewModel, expanded: Boolean, modifier: Modifier = Modifier) {
    val state by model.state.collectAsStateWithLifecycle()
    val p = state.active
    val focus = rememberTvFocus()
    Row(
        modifier.padding(start = 6.dp, bottom = 8.dp).then(focusLook(focus.focused, RoundedCornerShape(10.dp))).tvClick(focus) { model.openMenu() }.padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AvatarImage(p.avatar, 30.dp)
        if (expanded) Text(p.name, color = EnigmaColors.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp))
    }
}

/**
 * Under the profile chip: switch profile, Manage profiles, and the receiver's standby/wake
 * (the standby item that used to sit in the menu), then Close.
 */
@Composable
fun ProfileMenu(model: ProfileViewModel, powerLabel: String, powerAction: String, onPower: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = model::closeMenu, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(Unit) { first.requestWhenReady() }
        Column(
            Modifier.widthIn(min = 240.dp, max = 360.dp)
                .background(EnigmaColors.Selected, RoundedCornerShape(10.dp))
                .border(1.dp, EnigmaColors.CardBorder, RoundedCornerShape(10.dp))
                .padding(10.dp)
                .trapFocus(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.profile_switch), color = EnigmaColors.Muted, fontSize = 12.sp)
            state.profiles.forEachIndexed { i, p ->
                val focus = rememberTvFocus()
                Row(
                    Modifier.fillMaxWidth().focusRequesterIf(i == 0, first).then(focusLook(focus.focused, RoundedCornerShape(8.dp)))
                        .tvClick(focus) { model.choose(p.id) }.padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AvatarImage(p.avatar, 32.dp)
                    Text(p.name, color = EnigmaColors.Text, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (p.id == state.activeId) Text("✓", color = EnigmaColors.Cyan, fontSize = 14.sp)
                }
            }
            DialogButton(stringResource(R.string.profile_manage), { model.openPicker(manage = true) }, destructive = false, enabled = true, compact = true, modifier = Modifier.fillMaxWidth())
            Text(powerLabel, color = EnigmaColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            DialogButton(powerAction, { model.closeMenu(); onPower() }, destructive = false, enabled = true, compact = true, modifier = Modifier.fillMaxWidth())
            DialogButton(stringResource(R.string.close), model::closeMenu, destructive = false, enabled = true, compact = true, modifier = Modifier.fillMaxWidth())
        }
    }
}
