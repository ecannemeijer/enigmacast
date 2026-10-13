package com.enigmacast.ui.theme

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.enigmacast.ui.animation.focusTween
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.tv.material3.LocalContentColor
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
 * is in the content, so the user always sees where they are. A compact row, not a
 * TV list item: those are at least 40 dp tall and look bulky on a large screen.
 */
@Composable
fun NavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
    showLabel: Boolean = true,
    staggerIndex: Int = 0,
) {
    val spec = com.enigmacast.ui.animation.LocalMotionSpec.current
    val cursor = com.enigmacast.ui.components.LocalNavCursor.current
    val token = remember { Any() }
    val labelAlpha by animateFloatAsState(
        targetValue = if (showLabel) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (spec.animate) spec.sidebarMs else 0,
            delayMillis = if (showLabel && spec.animate) staggerIndex * 28 else 0,
        ),
        label = "navLabel",
    )
    CompactListRow(
        onClick = onClick,
        selected = selected,
        drawFocus = cursor == null,
        modifier = modifier.onFocusChanged {
            if (it.isFocused) onFocus()
            else if (cursor?.owner == token) cursor.visible = false
        },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 7.dp),
        onPlaced = { coords ->
            val rail = cursor ?: return@CompactListRow
            rail.owner = token
            rail.topPx = coords.positionInRoot().y - rail.originY
            rail.heightPx = coords.size.height.toFloat()
            rail.visible = true
        },
    ) { focused ->
        val iconScale by animateFloatAsState(
            targetValue = if (focused && spec.animate) 1.14f else 1f,
            animationSpec = spec.focusTween(),
            label = "navIcon",
        )
        val labelScale by animateFloatAsState(
            targetValue = if (focused && spec.animate) 1.16f else 1f,
            animationSpec = spec.focusTween(),
            label = "navLabelScale",
        )
        Icon(
            icon,
            contentDescription = if (showLabel) null else label,
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                },
        )
        if (labelAlpha > 0.02f) {
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                color = LocalContentColor.current,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer {
                    alpha = labelAlpha
                    scaleX = labelScale
                    scaleY = labelScale
                    transformOrigin = TransformOrigin(0f, 0.5f)
                },
            )
        }
        if (selected && focused && spec.glow && cursor == null) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .size(6.dp)
                    .background(EnigmaColors.Cyan, CircleShape),
            )
        }
    }
}

@Composable
fun PanelHeader(title: String, subtitle: String = "", modifier: Modifier = Modifier) {
    Column(modifier.padding(bottom = 8.dp)) {
        Text(title, color = EnigmaColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
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
        modifier = modifier.edgeLeft().fillMaxWidth(),
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
        scale = ListItemDefaults.scale(scale = 1f, focusedScale = 1.06f),
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
    selectedOptionModifier: Modifier = Modifier,
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
                    modifier = (if (index == 0) firstOptionModifier.edgeLeft() else Modifier)
                        .then(if (index == selectedIndex) selectedOptionModifier else Modifier),
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
        scale = ButtonDefaults.scale(scale = 1f, focusedScale = 1.08f),
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

/**
 * A value that moves in steps with two buttons, e.g. an audio delay. The value sits
 * between the buttons so left/right on the D-pad walks naturally through the row.
 * The buttons stay enabled at the ends of the range: a button that disables itself
 * while focused would throw D-pad focus out of the page. [onDecrease] and
 * [onIncrease] clamp instead.
 */
@Composable
fun StepperRow(
    title: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    decreaseLabel: String = "−",
    increaseLabel: String = "+",
    decreaseModifier: Modifier = Modifier,
    onReset: (() -> Unit)? = null,
    badge: String? = null,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = EnigmaColors.Text, fontSize = 15.sp)
            if (!badge.isNullOrBlank()) {
                Spacer(Modifier.width(8.dp))
                StatusPill(badge, EnigmaColors.Success)
            }
        }
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
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            ChoiceChip(label = decreaseLabel, selected = false, onClick = onDecrease, modifier = decreaseModifier.edgeLeft())
            Text(
                value,
                color = EnigmaColors.Accent,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.widthIn(min = 72.dp),
            )
            ChoiceChip(label = increaseLabel, selected = false, onClick = onIncrease)
            if (onReset != null) {
                ChoiceChip(label = "0", selected = false, onClick = onReset)
            }
        }
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
        scale = ButtonDefaults.scale(scale = 1f, focusedScale = 1.08f),
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

/**
 * Blocks the screen while the first channel list is fetched. Back and clicks outside do nothing;
 * the caller removes it when the list is in.
 */
@Composable
fun ChannelsLoadingDialog(message: String, progress: Float = 0f) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        NavigationSoundsForWindow()
        val hold = remember { FocusRequester() }
        val fraction = progress.coerceIn(0f, 1f)
        val animated by animateFloatAsState(fraction, tween(280), label = "channelLoad")
        val percent = (animated * 100).toInt()
        LaunchedEffect(Unit) { runCatching { hold.requestFocus() } }
        Column(
            modifier = Modifier
                .widthIn(min = 360.dp, max = 520.dp)
                .background(EnigmaColors.Selected, RoundedCornerShape(14.dp))
                .border(1.dp, EnigmaColors.CardBorder, RoundedCornerShape(14.dp))
                .padding(20.dp)
                .focusRequester(hold)
                .focusable(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CircularProgressIndicator(color = EnigmaColors.Accent, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                Text(
                    message,
                    color = EnigmaColors.Text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text("$percent%", color = EnigmaColors.Cyan, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = EnigmaColors.Cyan,
                trackColor = EnigmaColors.Raised,
            )
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
    compact: Boolean = false,
) {
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        NavigationSoundsForWindow()
        val confirmFocus = remember { FocusRequester() }
        val dismissFocus = remember { FocusRequester() }
        LaunchedEffect(busy) {
            if (!busy) runCatching { (if (focusConfirm) confirmFocus else dismissFocus).requestFocus() }
        }
        Column(
            modifier = Modifier
                .widthIn(min = if (compact) 240.dp else 320.dp, max = if (compact) 400.dp else 480.dp)
                .background(EnigmaColors.Selected, RoundedCornerShape(if (compact) 10.dp else 14.dp))
                .border(1.dp, EnigmaColors.CardBorder, RoundedCornerShape(if (compact) 10.dp else 14.dp))
                .padding(if (compact) 12.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
        ) {
            Text(title, color = EnigmaColors.Text, fontSize = if (compact) 15.sp else 18.sp, fontWeight = FontWeight.SemiBold)
            Text(message, color = EnigmaColors.Muted, fontSize = if (compact) 12.sp else 14.sp, lineHeight = if (compact) 16.sp else 19.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = if (compact) 2.dp else 6.dp)) {
                DialogButton(
                    label = confirmLabel,
                    onClick = onConfirm,
                    destructive = destructive,
                    enabled = !busy,
                    compact = compact,
                    modifier = Modifier.focusRequester(confirmFocus),
                )
                DialogButton(
                    label = dismissLabel,
                    onClick = onDismiss,
                    destructive = false,
                    enabled = !busy,
                    compact = compact,
                    modifier = Modifier.focusRequester(dismissFocus),
                )
            }
        }
    }
}

/**
 * A question with several answers in a row ([choices], the last usually "Cancel"). The first
 * answer gets focus; Back closes it like the last one.
 */
@Composable
fun TvChoiceDialog(
    title: String,
    message: String,
    choices: List<String>,
    onChoose: (Int) -> Unit,
    onDismiss: () -> Unit,
    compact: Boolean = false,
    /** Buttons stacked, as wide as the widest label (long labels, several choices). */
    stacked: Boolean = false,
) {
    if (stacked) {
        StackedChoiceDialog(title, message, choices, onChoose, onDismiss)
        return
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        NavigationSoundsForWindow()
        val firstFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
        Column(
            modifier = Modifier
                .widthIn(min = if (compact) 240.dp else 320.dp, max = if (compact) 420.dp else 480.dp)
                .background(EnigmaColors.Selected, RoundedCornerShape(if (compact) 10.dp else 14.dp))
                .border(1.dp, EnigmaColors.CardBorder, RoundedCornerShape(if (compact) 10.dp else 14.dp))
                .padding(if (compact) 12.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
        ) {
            Text(title, color = EnigmaColors.Text, fontSize = if (compact) 15.sp else 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(message, color = EnigmaColors.Muted, fontSize = if (compact) 12.sp else 14.sp, lineHeight = if (compact) 16.sp else 19.sp, maxLines = 3)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = if (compact) 2.dp else 6.dp)) {
                choices.forEachIndexed { index, label ->
                    DialogButton(
                        label = label,
                        onClick = { onChoose(index) },
                        destructive = false,
                        enabled = true,
                        compact = compact,
                        modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
            }
        }
    }
}

/** A small dialog sized to its content (max 420dp) with the choices under each other; the first has focus. */
@Composable
private fun StackedChoiceDialog(title: String, message: String, choices: List<String>, onChoose: (Int) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        NavigationSoundsForWindow()
        val firstFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { firstFocus.requestWhenReady() }
        Column(
            modifier = Modifier
                .widthIn(min = 220.dp, max = 420.dp)
                .width(androidx.compose.foundation.layout.IntrinsicSize.Max)
                .background(EnigmaColors.Selected, RoundedCornerShape(10.dp))
                .border(1.dp, EnigmaColors.CardBorder, RoundedCornerShape(10.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, color = EnigmaColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            if (message.isNotBlank()) Text(message, color = EnigmaColors.Muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 3)
            choices.forEachIndexed { index, label ->
                DialogButton(
                    label = label,
                    onClick = { onChoose(index) },
                    destructive = false,
                    enabled = true,
                    compact = true,
                    modifier = Modifier.fillMaxWidth().focusRequesterIf(index == 0, firstFocus),
                )
            }
        }
    }
}

@Composable
internal fun DialogButton(
    label: String,
    onClick: () -> Unit,
    destructive: Boolean,
    enabled: Boolean,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        scale = ButtonDefaults.scale(scale = 1f, focusedScale = 1.08f),
        contentPadding = PaddingValues(
            horizontal = if (compact) 12.dp else 24.dp,
            vertical = if (compact) 4.dp else 10.dp,
        ),
        colors = ButtonDefaults.colors(
            containerColor = EnigmaColors.Raised,
            contentColor = if (destructive) EnigmaColors.Danger else EnigmaColors.Text,
            focusedContainerColor = if (destructive) EnigmaColors.DangerFocus else EnigmaColors.Accent,
            focusedContentColor = if (destructive) EnigmaColors.OnFocus else EnigmaColors.OnAccent,
            pressedContainerColor = if (destructive) EnigmaColors.DangerFocus else EnigmaColors.Accent,
            pressedContentColor = if (destructive) EnigmaColors.OnFocus else EnigmaColors.OnAccent,
        ),
    ) {
        Text(label, fontSize = if (compact) 13.sp else 16.sp)
    }
}

/** A small button that sits in a row of [CompactField]s (about the height of the input box). */
@Composable
fun CompactButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(8.dp)),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        scale = ButtonDefaults.scale(scale = 1f, focusedScale = 1.08f),
        colors = ButtonDefaults.colors(
            containerColor = EnigmaColors.Raised,
            contentColor = EnigmaColors.Text,
            focusedContainerColor = EnigmaColors.Accent,
            focusedContentColor = EnigmaColors.OnAccent,
            pressedContainerColor = EnigmaColors.Accent,
            pressedContentColor = EnigmaColors.OnAccent,
        ),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, fontSize = 14.sp, maxLines = 1)
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

/**
 * A setting with several values as one compact row: title, description and the current value
 * on the right. OK opens [OptionListDialog]. Replaces chip rows that do not fit on a TV screen.
 */
@Composable
fun ValueRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    ListItem(
        selected = false,
        onClick = onClick,
        modifier = modifier.edgeLeft().fillMaxWidth(),
        headlineContent = { Text(title, fontSize = 15.sp, maxLines = 1) },
        supportingContent = if (description != null) {
            { Text(description, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2) }
        } else {
            null
        },
        trailingContent = {
            Text("$value  ›", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        },
        colors = rowColors(),
        shape = ListItemDefaults.shape(shape = RowShape),
        scale = ListItemDefaults.scale(scale = 1f, focusedScale = 1.03f),
    )
}

/** [ValueRow] with its option list: the current option is checked and gets focus; Back closes. */
@Composable
fun ValueSetting(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    ValueRow(title, options.getOrElse(selectedIndex) { "" }, { open = true }, modifier, description)
    if (open) {
        OptionListDialog(title, options, selectedIndex, onSelect = { open = false; onSelect(it) }, onDismiss = { open = false })
    }
}

@Composable
fun OptionListDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        NavigationSoundsForWindow()
        val current = remember { FocusRequester() }
        LaunchedEffect(Unit) { current.requestWhenReady() }
        Column(
            modifier = Modifier
                .widthIn(min = 280.dp, max = 380.dp)
                .heightIn(max = 470.dp)
                .background(EnigmaColors.Selected, RoundedCornerShape(14.dp))
                .border(1.dp, EnigmaColors.CardBorder, RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, color = EnigmaColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEachIndexed { index, label ->
                    ListItem(
                        selected = index == selectedIndex,
                        onClick = { onSelect(index) },
                        modifier = Modifier.fillMaxWidth().focusRequesterIf(index == selectedIndex.coerceAtLeast(0), current),
                        headlineContent = { Text(label, fontSize = 15.sp) },
                        trailingContent = if (index == selectedIndex) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                        colors = rowColors(),
                        shape = ListItemDefaults.shape(shape = RowShape),
                        scale = ListItemDefaults.scale(scale = 1f, focusedScale = 1.02f),
                    )
                }
            }
        }
    }
}

/**
 * Scrolls a settings column only as far as needed to show the focused row, instead of the
 * TV default that pulls focus towards the middle and leaves a big empty band above.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
val MinimalBringIntoView = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailing = offset + size
        return when {
            offset >= 0f && trailing <= containerSize -> 0f
            offset < 0f || size > containerSize -> offset
            else -> trailing - containerSize
        }
    }
}
