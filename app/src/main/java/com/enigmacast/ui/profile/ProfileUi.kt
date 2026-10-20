package com.enigmacast.ui.profile

import com.enigmacast.ui.theme.skinCanvas
import com.enigmacast.ui.theme.skinPane
import com.enigmacast.ui.theme.EnigmaType
import com.enigmacast.ui.theme.skinShape
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import com.enigmacast.domain.security.PinHash
import com.enigmacast.ui.security.PinPad
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

/** The profile's phone photo when it has one, else its bundled avatar. Decoded off the main thread. */
@Composable
fun ProfileAvatar(profile: Profile, size: Dp, modifier: Modifier = Modifier) {
    if (profile.photo == 0L) return AvatarImage(profile.avatar, size, modifier)
    val context = androidx.compose.ui.platform.LocalContext.current
    val uuid = profile.uuid.ifBlank { ProfileState.LEGACY_UUID }
    val bitmap by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, uuid, profile.photo) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                android.graphics.BitmapFactory.decodeFile(com.enigmacast.data.profile.AvatarPhotos.fileFor(context, uuid).path)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val image = bitmap
    if (image == null) AvatarImage(profile.avatar, size, modifier)
    else Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.size(size).clip(RoundedCornerShape(size / 5)))
}

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
    Dialog(onDismissRequest = model::closePicker, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true)) {
        ProfilePickerContent(model)
    }
}

/** Full-screen "Who's watching?" without a dialog, so the dashboard is not built underneath. */
@Composable
internal fun ProfilePickerContent(model: ProfileViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var pinSetup by remember { mutableStateOf<Profile?>(null) }
    val first = remember { FocusRequester() }
    val editing = state.profiles.firstOrNull { it.id == model.editorId }
    LaunchedEffect(Unit) { first.requestWhenReady() }
    Column(
            Modifier.fillMaxSize().skinCanvas().padding(48.dp).trapFocus(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                stringResource(if (model.managing) R.string.profile_manage else R.string.profile_who),
                color = EnigmaColors.Text, fontSize = 30.sp, fontWeight = EnigmaType.Strong,
            )
            Row(Modifier.padding(top = 32.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                state.profiles.forEachIndexed { i, p ->
                    ProfileTile(p.name, p.avatar, p.id == state.activeId, model.managing, Modifier.focusRequesterIf(i == 0, first), photo = p) {
                        model.choose(p.id)
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
    editing?.let { p ->
        ProfileEditor(
            title = stringResource(R.string.profile_edit),
            initialName = p.name, initialAvatar = p.avatar,
            canDelete = model.canDelete(p.id),
            hasPin = model.hasPin(p),
            playlist = model.seesPlaylist(p),
            onSave = { name, avatar, playlist -> model.edit(p.id, name, avatar, playlist) },
            onDelete = { model.delete(p.id) },
            onDismiss = model::closeEditor,
            showPin = true,
            onSetPin = { pinSetup = p },
            onClearPin = { model.clearPin(p.id) },
            photo = p,
            onPhotoQr = { model.startPhotoQr(p.id) },
            onClearPhoto = { model.clearPhoto(p.id) },
            qrOpen = model.photoQr != null,
        )
    }
    model.photoQr?.let { qr -> PhotoQrDialog(qr, onRetry = { editing?.let { model.startPhotoQr(it.id) } }, onClose = model::stopPhotoQr) }
    if (adding) {
        ProfileEditor(
            title = stringResource(R.string.profile_add),
            initialName = "", initialAvatar = state.profiles.size % ProfileState.AVATAR_COUNT,
            canDelete = false,
            onSave = { name, avatar, playlist ->
                val id = model.add(name, avatar)
                model.edit(id, name, avatar, playlist)
                adding = false
            },
            onDelete = {},
            onDismiss = { adding = false },
        )
    }
    val locking = model.pinProfile
    if (locking != null) {
        val wrong = stringResource(R.string.pin_wrong)
        val cool = stringResource(R.string.pin_cooldown)
        Dialog(onDismissRequest = model::cancelPin, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            PinPad(
                title = stringResource(R.string.profile_pin_title, locking.name),
                error = model.pinError,
                busy = false,
                onSubmit = { model.submitPin(it, { n -> wrong.format(n) }, { n -> cool.format(n) }) },
                onCancel = model::cancelPin,
            )
        }
    }
    pinSetup?.let { profile ->
        PinSetupDialog(
            onSave = { pin -> model.setPin(profile.id, pin); pinSetup = null },
            onDismiss = { pinSetup = null },
        )
    }
}

private const val MAX_PROFILES = 6

@Composable
internal fun ProfileTile(name: String, avatar: Int, active: Boolean, managing: Boolean, modifier: Modifier, photo: Profile? = null, onClick: () -> Unit) {
    val focus = rememberTvFocus()
    Column(
        modifier.width(132.dp).tvClick(focus, onClick).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
            val ring = Modifier.border(if (focus.focused) 4.dp else 0.dp, if (focus.focused) Color.White else Color.Transparent, skinShape(24.dp))
            if (photo != null) ProfileAvatar(photo, 120.dp, ring) else AvatarImage(avatar, 120.dp, ring)
            if (managing) {
                Text("✎", color = Color.White, fontSize = 18.sp, modifier = Modifier.align(Alignment.Center).background(Color(0x99000000), CircleShape).padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        Text(
            name, color = if (focus.focused || active) EnigmaColors.Text else EnigmaColors.Muted,
            fontSize = 15.sp, fontWeight = if (active) EnigmaType.Strong else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
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
            Modifier.size(120.dp).clip(skinShape(24.dp)).background(EnigmaColors.Raised)
                .border(if (focus.focused) 4.dp else 1.dp, if (focus.focused) Color.White else EnigmaColors.CardBorder, skinShape(24.dp)),
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
    onSave: (String, Int, Boolean) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    hasPin: Boolean = false,
    playlist: Boolean = true,
    showPin: Boolean = false,
    onSetPin: () -> Unit = {},
    onClearPin: () -> Unit = {},
    /** The profile being edited, for its phone photo; null when adding. */
    photo: Profile? = null,
    onPhotoQr: (() -> Unit)? = null,
    onClearPhoto: () -> Unit = {},
    /** The QR dialog is on top; when it closes, focus goes back to the QR button. */
    qrOpen: Boolean = false,
) {
    val qrButton = remember { FocusRequester() }
    var qrWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(qrOpen) {
        if (qrOpen) qrWasOpen = true
        else if (qrWasOpen) { qrWasOpen = false; qrButton.requestWhenReady() }
    }
    var name by remember { mutableStateOf(initialName) }
    var avatar by remember { mutableIntStateOf(initialAvatar) }
    val stamp = photo?.photo ?: 0L
    // A photo that arrives while the editor is open is selected right away.
    var photoOn by remember(stamp) { mutableStateOf(stamp > 0L) }
    var playlistOn by remember { mutableStateOf(playlist) }
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
                .background(EnigmaColors.Selected, skinShape(10.dp))
                .border(1.dp, EnigmaColors.CardBorder, skinShape(10.dp))
                .padding(14.dp)
                .trapFocus(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, color = EnigmaColors.Text, fontSize = 16.sp, fontWeight = EnigmaType.Strong)
            TvTextField(stringResource(R.string.profile_name), name, { name = it.take(20) }, fieldFocus = field)
            Text(stringResource(R.string.profile_avatar), color = EnigmaColors.Muted, fontSize = 12.sp)
            for (row in 0 until 2) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (col in 0 until 8) {
                        val i = row * 8 + col
                        val focus = rememberTvFocus()
                        Box(
                            Modifier.tvClick(focus) { avatar = i; photoOn = false }
                                .border(
                                    if (focus.focused || (!photoOn && avatar == i)) 3.dp else 0.dp,
                                    when { focus.focused -> Color.White; !photoOn && avatar == i -> EnigmaColors.Cyan; else -> Color.Transparent },
                                    skinShape(12.dp),
                                ).padding(3.dp),
                        ) { AvatarImage(i, 52.dp) }
                    }
                }
            }
            if (onPhotoQr != null && photo != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (stamp > 0L) {
                        val focus = rememberTvFocus()
                        Box(
                            Modifier.tvClick(focus) { photoOn = true }
                                .border(
                                    if (focus.focused || photoOn) 3.dp else 0.dp,
                                    when { focus.focused -> Color.White; photoOn -> EnigmaColors.Cyan; else -> Color.Transparent },
                                    skinShape(12.dp),
                                ).padding(3.dp),
                        ) { ProfileAvatar(photo, 52.dp) }
                    }
                    DialogButton(stringResource(R.string.profile_photo_qr), onPhotoQr, destructive = false, enabled = true, compact = true, modifier = Modifier.focusRequester(qrButton))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(stringResource(R.string.profile_save), { if (name.isNotBlank()) { if (stamp > 0L && !photoOn) onClearPhoto(); onSave(name, avatar, playlistOn) } }, destructive = false, enabled = name.isNotBlank(), compact = true)
                if (canDelete) DialogButton(stringResource(R.string.profile_delete), { confirmDelete = true }, destructive = true, enabled = true, compact = true)
                if (showPin) {
                    DialogButton(stringResource(if (hasPin) R.string.profile_pin_clear else R.string.profile_pin_set), { if (hasPin) onClearPin() else onSetPin() }, destructive = false, enabled = true, compact = true)
                }
                DialogButton(
                    stringResource(if (playlistOn) R.string.profile_playlist_on else R.string.profile_playlist_off),
                    { playlistOn = !playlistOn }, destructive = false, enabled = true, compact = true,
                )
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
        modifier.padding(start = 6.dp, bottom = 8.dp).then(focusLook(focus.focused, skinShape(10.dp))).tvClick(focus) { model.openMenu() }.padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ProfileAvatar(p, 30.dp)
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
                .background(EnigmaColors.Selected, skinShape(10.dp))
                .border(1.dp, EnigmaColors.CardBorder, skinShape(10.dp))
                .padding(10.dp)
                .trapFocus(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.profile_switch), color = EnigmaColors.Muted, fontSize = 12.sp)
            state.profiles.forEachIndexed { i, p ->
                val focus = rememberTvFocus()
                Row(
                    Modifier.fillMaxWidth().focusRequesterIf(i == 0, first).then(focusLook(focus.focused, skinShape(8.dp)))
                        .tvClick(focus) { model.choose(p.id) }.padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ProfileAvatar(p, 32.dp)
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

/** Two-step PIN entry. The digits stay in memory only until [onSave]. */
@Composable
private fun PinSetupDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val mismatch = stringResource(R.string.pin_mismatch)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        PinPad(
            title = stringResource(if (first == null) R.string.pin_new_title else R.string.pin_repeat_title),
            error = error,
            busy = false,
            onSubmit = { pin ->
                if (!PinHash.valid(pin)) return@PinPad
                val previous = first
                if (previous == null) {
                    first = pin
                    error = null
                } else if (previous == pin) onSave(pin) else {
                    first = null
                    error = mismatch
                }
            },
            onCancel = onDismiss,
        )
    }
}
