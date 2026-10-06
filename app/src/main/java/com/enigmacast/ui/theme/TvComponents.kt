package com.enigmacast.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.Switch
import androidx.tv.material3.SwitchDefaults
import androidx.tv.material3.Text

private val RowShape = RoundedCornerShape(10.dp)
private val CardShape = RoundedCornerShape(14.dp)
private val TileShape = RoundedCornerShape(12.dp)

@Composable
private fun rowColors() = ListItemDefaults.colors(
    containerColor = Color.Transparent,
    contentColor = EnigmaColors.Text,
    focusedContainerColor = EnigmaColors.Focus,
    focusedContentColor = EnigmaColors.OnFocus,
    pressedContainerColor = EnigmaColors.Focus,
    pressedContentColor = EnigmaColors.OnFocus,
    selectedContainerColor = EnigmaColors.Selected,
    selectedContentColor = EnigmaColors.Accent,
    focusedSelectedContainerColor = EnigmaColors.Focus,
    focusedSelectedContentColor = EnigmaColors.OnFocus,
    pressedSelectedContainerColor = EnigmaColors.Focus,
    pressedSelectedContentColor = EnigmaColors.OnFocus,
)

/**
 * Side menu and submenu entry. [selected] keeps the open section marked while focus
 * is in the content, so the user always sees where they are.
 */
@Composable
fun NavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
) {
    ListItem(
        selected = selected,
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocus() },
        headlineContent = {
            Text(label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        },
        colors = rowColors(),
        shape = ListItemDefaults.shape(shape = RowShape),
        scale = ListItemDefaults.scale(scale = 1f, focusedScale = 1.03f),
    )
}

@Composable
fun PanelHeader(title: String, subtitle: String = "", modifier: Modifier = Modifier) {
    Column(modifier.padding(bottom = 12.dp)) {
        Text(title, color = EnigmaColors.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                color = EnigmaColors.Muted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** Groups related settings on a slightly raised card with a small amber caption. */
@Composable
fun SettingsCard(
    title: String?,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(EnigmaColors.Card, CardShape)
            .border(1.dp, EnigmaColors.CardBorder, CardShape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (title != null || trailing != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title.orEmpty().uppercase(),
                    color = EnigmaColors.Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.weight(1f),
                )
                trailing?.invoke()
            }
        }
        content()
    }
}

/** A full-width row that flips a setting with OK; the switch only shows the state. */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
) {
    ListItem(
        selected = false,
        onClick = { onCheckedChange(!checked) },
        modifier = modifier.fillMaxWidth(),
        headlineContent = { Text(title, fontSize = 15.sp) },
        supportingContent = if (description != null) {
            { Text(description, fontSize = 12.sp, lineHeight = 16.sp) }
        } else {
            null
        },
        leadingContent = if (icon != null) {
            { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) }
        } else {
            null
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = EnigmaColors.OnAccent,
                    checkedTrackColor = EnigmaColors.Accent,
                    uncheckedThumbColor = EnigmaColors.Muted,
                    uncheckedTrackColor = EnigmaColors.Raised,
                ),
            )
        },
        colors = rowColors(),
        shape = ListItemDefaults.shape(shape = RowShape),
        scale = ListItemDefaults.scale(scale = 1f, focusedScale = 1.01f),
    )
}

/** A labelled set of mutually exclusive choices, e.g. Stabiel / Snel. */
@Composable
fun ChoiceRow(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    firstOptionModifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(title, color = EnigmaColors.Text, fontSize = 15.sp)
        if (!description.isNullOrBlank()) {
            Text(
                description,
                color = EnigmaColors.Muted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            options.forEachIndexed { index, label ->
                ChoiceChip(
                    label = label,
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    modifier = if (index == 0) firstOptionModifier else Modifier,
                )
            }
        }
    }
}

@Composable
fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) EnigmaColors.Selected else EnigmaColors.Raised,
            contentColor = if (selected) EnigmaColors.Accent else EnigmaColors.Text,
            focusedContainerColor = EnigmaColors.Focus,
            focusedContentColor = EnigmaColors.OnFocus,
            pressedContainerColor = EnigmaColors.Focus,
            pressedContentColor = EnigmaColors.OnFocus,
        ),
    ) {
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, fontSize = 14.sp)
    }
}

enum class TileTone {
    Normal,
    Primary,
    Danger,
}

/** A large, D-pad friendly action button with an icon and an optional hint underneath. */
@Composable
fun ActionTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    tone: TileTone = TileTone.Normal,
    enabled: Boolean = true,
) {
    val container = when (tone) {
        TileTone.Primary -> Color(0xFF2A2110)
        TileTone.Danger -> Color(0xFF2A1414)
        TileTone.Normal -> EnigmaColors.Raised
    }
    val content = when (tone) {
        TileTone.Primary -> EnigmaColors.Accent
        TileTone.Danger -> EnigmaColors.Danger
        TileTone.Normal -> EnigmaColors.Text
    }
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = ButtonDefaults.shape(shape = TileShape),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        scale = ButtonDefaults.scale(scale = 1f, focusedScale = 1.04f),
        colors = ButtonDefaults.colors(
            containerColor = container,
            contentColor = content,
            focusedContainerColor = if (tone == TileTone.Danger) EnigmaColors.DangerFocus else EnigmaColors.Focus,
            focusedContentColor = EnigmaColors.OnFocus,
            pressedContainerColor = if (tone == TileTone.Danger) EnigmaColors.DangerFocus else EnigmaColors.Focus,
            pressedContentColor = EnigmaColors.OnFocus,
            disabledContainerColor = container.copy(alpha = 0.4f),
            disabledContentColor = content.copy(alpha = 0.5f),
        ),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!description.isNullOrBlank()) {
                Text(
                    description,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(EnigmaColors.Raised, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, color = EnigmaColors.Text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    if (value.isBlank()) return
    Row(modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(label, color = EnigmaColors.Muted, fontSize = 13.sp, modifier = Modifier.width(110.dp))
        Text(value, color = EnigmaColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

/**
 * Modal question in its own window, so the D-pad cannot wander to the screen behind it
 * and Back closes it. For risky actions [focusConfirm] is false and Cancel gets focus.
 */
@Composable
fun TvConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    busy: Boolean = false,
    focusConfirm: Boolean = !destructive,
) {
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val confirmFocus = remember { FocusRequester() }
        val dismissFocus = remember { FocusRequester() }
        LaunchedEffect(busy) {
            if (!busy) runCatching { (if (focusConfirm) confirmFocus else dismissFocus).requestFocus() }
        }
        Column(
            modifier = Modifier
                .widthIn(min = 420.dp, max = 560.dp)
                .background(EnigmaColors.Selected, RoundedCornerShape(18.dp))
                .border(1.dp, EnigmaColors.CardBorder, RoundedCornerShape(18.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, color = EnigmaColors.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(message, color = EnigmaColors.Muted, fontSize = 16.sp, lineHeight = 22.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                DialogButton(
                    label = confirmLabel,
                    onClick = onConfirm,
                    destructive = destructive,
                    enabled = !busy,
                    modifier = Modifier.focusRequester(confirmFocus),
                )
                DialogButton(
                    label = dismissLabel,
                    onClick = onDismiss,
                    destructive = false,
                    enabled = !busy,
                    modifier = Modifier.focusRequester(dismissFocus),
                )
            }
        }
    }
}

@Composable
private fun DialogButton(
    label: String,
    onClick: () -> Unit,
    destructive: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
        colors = ButtonDefaults.colors(
            containerColor = EnigmaColors.Raised,
            contentColor = if (destructive) EnigmaColors.Danger else EnigmaColors.Text,
            focusedContainerColor = if (destructive) EnigmaColors.DangerFocus else EnigmaColors.Accent,
            focusedContentColor = if (destructive) EnigmaColors.OnFocus else EnigmaColors.OnAccent,
            pressedContainerColor = if (destructive) EnigmaColors.DangerFocus else EnigmaColors.Accent,
            pressedContentColor = if (destructive) EnigmaColors.OnFocus else EnigmaColors.OnAccent,
        ),
    ) {
        Text(label, fontSize = 16.sp)
    }
}

/** Short confirmation or error at the bottom of the screen; it never takes focus. */
@Composable
fun FeedbackSnackbar(message: String, isError: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .widthIn(max = 560.dp)
            .background(EnigmaColors.Raised, RoundedCornerShape(12.dp))
            .border(1.dp, if (isError) EnigmaColors.Danger else EnigmaColors.CardBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (isError) Icons.Filled.Warning else Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = if (isError) EnigmaColors.Danger else EnigmaColors.Success,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(message, color = EnigmaColors.Text, fontSize = 15.sp)
    }
}
