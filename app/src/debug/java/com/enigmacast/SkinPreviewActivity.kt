package com.enigmacast

import com.enigmacast.R

import android.content.Intent
import android.os.Bundle
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.enigmacast.ui.components.EnigmaCastLogo
import com.enigmacast.ui.components.EnigmaNavigationDrawer
import com.enigmacast.ui.dashboard.DashFlyout
import com.enigmacast.ui.dashboard.DashFlyoutEntry
import com.enigmacast.ui.theme.NavIconMotion
import com.enigmacast.ui.theme.NavIconStyle
import com.enigmacast.ui.theme.NavItem
import com.enigmacast.ui.theme.skinCanvas
import com.enigmacast.ui.theme.skinPane
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ChannelGuide
import com.enigmacast.domain.model.EpgEvent
import com.enigmacast.domain.model.OsdInfoFields
import com.enigmacast.domain.model.OsdSkinId
import com.enigmacast.domain.model.PlayerState
import com.enigmacast.domain.osdinfo.OsdInfo
import com.enigmacast.live.TunerState
import com.enigmacast.ui.remote.ChannelBanner
import com.enigmacast.ui.remote.PliColourHints
import com.enigmacast.ui.remote.PliHd
import com.enigmacast.ui.settings.SkinChooser
import com.enigmacast.ui.theme.ActiveSkin
import com.enigmacast.ui.theme.ChoiceRow
import com.enigmacast.ui.theme.EnigmaCastTheme
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.SettingsCard
import com.enigmacast.ui.theme.ToggleRow
import com.enigmacast.util.UiText

/** Debug-only screen with sample data: a settings card and the channel banner in one skin. */
class SkinPreviewActivity : ComponentActivity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = runCatching { OsdSkinId.valueOf(intent.getStringExtra("skin").orEmpty()) }.getOrDefault(OsdSkinId.STANDARD)
        val mode = intent.getStringExtra("mode").orEmpty().lowercase()
        val lang = intent.getStringExtra("lang")
        val flyout = intent.getStringExtra("flyout").orEmpty().lowercase()
        if (!lang.isNullOrBlank()) {
            val locale = java.util.Locale.forLanguageTag(lang)
            java.util.Locale.setDefault(locale)
            val config = Configuration(resources.configuration)
            config.setLocale(locale)
            @Suppress("DEPRECATION")
            resources.updateConfiguration(config, resources.displayMetrics)
        }
        val previewContext = this
        ActiveSkin.apply(id)
        val now = System.currentTimeMillis() / 1000
        val start = now - 25 * 60
        val events = listOf(
            EpgEvent("NOS Journaal", "", "Het laatste nieuws uit binnen- en buitenland.", label(start), label(start + 45 * 60), start, 45 * 60, true),
            EpgEvent("Studio Sport Eredivisie", "", "", label(start + 45 * 60), label(start + 105 * 60), start + 45 * 60, 60 * 60, false),
        )
        setContent {
            CompositionLocalProvider(LocalContext provides previewContext) {
            EnigmaCastTheme {
                when (mode) {
                    "menu" -> MenuPreview(flyout)
                    "list" -> PliListPreview(now)
                    "epg" -> PliEpgPreview(now, events)
                    "infobar" -> InfobarOnlyPreview(id, events)
                    "chooser" -> ChooserPreview(id)
                    "iptv-provider" -> IptvProviderPreview(before = false)
                    "iptv-provider-before" -> IptvProviderPreview(before = true)
                    else -> DefaultPreview(id, events, start)
                }
            }
            }
        }
    }

    @Composable
    private fun DefaultPreview(id: OsdSkinId, events: List<EpgEvent>, start: Long) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(listOf(Color(0xFF3B6E8F), Color(0xFF7A9A5B), Color(0xFFB98A54))),
            ),
        ) {
            Row(Modifier.padding(48.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.width(900.dp).background(EnigmaColors.Canvas).padding(16.dp)) {
                    SettingsCard(getString(R.string.nav_osd_skins)) {
                        SkinChooser(id, {})
                    }
                    SettingsCard(getString(R.string.osd_transparency)) {
                        ToggleRow(title = getString(R.string.fast_refresh), checked = true, onCheckedChange = {}, description = getString(R.string.fast_refresh_desc))
                        ChoiceRow(title = getString(R.string.osd_format), options = listOf("PNG", "JPG"), selectedIndex = 0, onSelect = {})
                    }
                }
                Text(getString(id.label), color = Color.White, fontSize = 40.sp)
            }
            ChannelBanner(
                channel = BouquetEntry("NPO 1 HD", "1:0:19:17C0:C:1:FFFF0000:0:0:0:", 1),
                number = 1,
                guide = ChannelGuide("NPO 1 HD", "", events),
                player = PlayerState(playbackState = "READY", videoWidth = 1920, videoHeight = 1080, audioLabel = UiText.Raw("Nederlands · AC3 5.1")),
                tuner = TunerState(),
                details = false,
                optionsHint = true,
                recording = false,
                osdInfo = OsdInfo(),
                osdFields = OsdInfoFields(),
                osdSkin = id,
                picon = { null },
                cachedPicon = { null },
                scrim = EnigmaColors.scrim(10),
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    @Composable
    private fun PliListPreview(now: Long) {
        val channels = listOf(
            Triple(1, "NPO 1 HD", "NOS Journaal"),
            Triple(2, "NPO 2 HD", "Andere Tijden"),
            Triple(3, "NPO 3 HD", "Jeugdjournaal"),
            Triple(4, "RTL 4 HD", "Goede Tijden"),
            Triple(5, "SBS 6 HD", "Shownieuws"),
            Triple(7, "Veronica HD", "Crime Desk"),
            Triple(10, "BBC One HD", "EastEnders"),
            Triple(12, "CNN HD", "World News"),
        )
        Box(Modifier.fillMaxSize().background(Color(0xFF1A3040))) {
            Column(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(1100.dp)
                    .background(PliHd.TranspBlack)
                    .border(1.dp, PliHd.DarkGrey),
            ) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.weight(1f).padding(16.dp)) {
                        Text("Favourites (TV)", color = PliHd.SecondFg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif)
                        Text(getString(R.string.pli_list_hint), color = PliHd.Grey, fontSize = 11.sp, modifier = Modifier.padding(bottom = 10.dp))
                        channels.forEachIndexed { index, (num, name, title) ->
                            val focused = index == 0
                            val bg = if (focused) Brush.verticalGradient(PliHd.Selection) else null
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .then(if (bg != null) Modifier.background(bg) else Modifier)
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("%04d".format(num), color = PliHd.Foreground, fontSize = 13.sp, modifier = Modifier.width(48.dp))
                                Text(name, color = PliHd.Foreground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.4f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Column(Modifier.weight(0.6f)) {
                                    Text(title, color = if (focused) PliHd.SecondFg else PliHd.Grey, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Box(Modifier.fillMaxWidth().height(4.dp).background(PliHd.ProgressBg).padding(top = 2.dp)) {
                                        Box(Modifier.fillMaxWidth(0.55f).fillMaxHeight().background(Brush.verticalGradient(PliHd.ProgressFill)))
                                    }
                                }
                            }
                        }
                    }
                    Box(Modifier.fillMaxHeight().width(1.dp).background(PliHd.DarkGrey))
                    Column(Modifier.width(360.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(getString(R.string.pli_event_info), color = PliHd.SecondFg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("NPO 1 HD", color = PliHd.Foreground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("NOS Journaal", color = PliHd.SecondFg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("20:00 – 20:45  (45 min)", color = PliHd.Grey, fontSize = 12.sp)
                        Box(Modifier.fillMaxWidth().height(6.dp).background(PliHd.ProgressBg)) {
                            Box(Modifier.fillMaxWidth(0.55f).fillMaxHeight().background(Brush.verticalGradient(PliHd.ProgressFill)))
                        }
                        Text(getString(R.string.pli_event_open_epg), color = PliHd.Grey, fontSize = 12.sp)
                    }
                }
                PliColourHints(Modifier.fillMaxWidth().background(PliHd.NoTransBg).padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }

    @Composable
    private fun PliEpgPreview(now: Long, events: List<EpgEvent>) {
        Box(Modifier.fillMaxSize().background(Color(0xFF1A3040))) {
            Column(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(1000.dp)
                    .background(PliHd.TranspBlack)
                    .border(1.dp, PliHd.DarkGrey)
                    .padding(16.dp),
            ) {
                Text("NPO 1 HD", color = PliHd.SecondFg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(getString(R.string.epg_panel_hint), color = PliHd.Grey, fontSize = 11.sp, modifier = Modifier.padding(bottom = 10.dp))
                events.forEachIndexed { index, event ->
                    val focused = index == 0
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(if (focused) Modifier.background(Brush.verticalGradient(PliHd.Selection)) else Modifier)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(event.beginLabel, color = if (focused) PliHd.SecondFg else PliHd.Grey, fontSize = 13.sp, modifier = Modifier.width(56.dp))
                        Column(Modifier.weight(1f)) {
                            Text(event.title, color = PliHd.Foreground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            if (focused) {
                                Box(Modifier.fillMaxWidth().height(4.dp).background(PliHd.ProgressBg).padding(top = 3.dp)) {
                                    Box(Modifier.fillMaxWidth(0.55f).fillMaxHeight().background(Brush.verticalGradient(PliHd.ProgressFill)))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(events.first().title, color = PliHd.SecondFg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(events.first().plot.ifBlank { "Het laatste nieuws uit binnen- en buitenland." }, color = PliHd.Foreground, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                PliColourHints(Modifier.fillMaxWidth().background(PliHd.NoTransBg).padding(horizontal = 8.dp, vertical = 8.dp))
            }
        }
    }


    @Composable
    private fun InfobarOnlyPreview(id: OsdSkinId, events: List<EpgEvent>) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(listOf(Color(0xFF2A4058), Color(0xFF1A2838), Color(0xFF0E1824))),
            ),
        ) {
            ChannelBanner(
                channel = BouquetEntry("NPO 1 HD", "1:0:19:17C0:C:1:FFFF0000:0:0:0:", 1),
                number = 1,
                guide = ChannelGuide("NPO 1 HD", "", events),
                player = PlayerState(playbackState = "READY", videoWidth = 1920, videoHeight = 1080, audioLabel = UiText.Raw("Nederlands · AC3 5.1")),
                tuner = TunerState(),
                details = false,
                optionsHint = true,
                recording = false,
                osdInfo = OsdInfo(),
                osdFields = OsdInfoFields(),
                osdSkin = id,
                picon = { null },
                cachedPicon = { null },
                scrim = EnigmaColors.scrim(10),
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    @Composable
    private fun ChooserPreview(id: OsdSkinId) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0A1428))) {
            Column(Modifier.padding(48.dp).width(980.dp).background(EnigmaColors.Canvas).padding(16.dp)) {
                SettingsCard(getString(R.string.nav_osd_skins)) {
                    SkinChooser(id, {})
                }
            }
        }
    }


    /** Debug menu chrome. Locks stay off: the app is freeware, and PremiumGate already unlocks every item in Free mode. */
    @Composable
    private fun MenuPreview(flyout: String) {
        val homeFocus = remember { FocusRequester() }
        val tvFocus = remember { FocusRequester() }
        val iptvFocus = remember { FocusRequester() }
        val flyoutFocus = remember { FocusRequester() }
        val showTv = flyout == "tv"
        val showIptv = flyout == "iptv"
        LaunchedEffect(flyout) {
            when {
                showTv || showIptv -> flyoutFocus.requestFocus()
                else -> homeFocus.requestFocus()
            }
        }
        Row(Modifier.fillMaxSize().skinCanvas()) {
            EnigmaNavigationDrawer(expanded = true) {
                EnigmaCastLogo(expanded = true)
                NavItem(
                    icon = NavIconStyle.Home,
                    label = stringResource(R.string.nav_home),
                    selected = !showTv && !showIptv,
                    onClick = {},
                    modifier = Modifier.focusRequester(homeFocus),
                    iconMotion = NavIconMotion.PULSE,
                )
                NavItem(
                    icon = NavIconStyle.Enigma,
                    label = stringResource(R.string.nav_tv),
                    selected = showTv,
                    onClick = {},
                    modifier = Modifier.focusRequester(tvFocus),
                    iconMotion = NavIconMotion.WOBBLE,
                )
                if (showTv) {
                    DashFlyout(
                        entries = listOf(
                            DashFlyoutEntry("live", stringResource(R.string.nav_live), false),
                            DashFlyoutEntry("guide", stringResource(R.string.nav_guide), false),
                            DashFlyoutEntry("movies", stringResource(R.string.nav_movies), false),
                            DashFlyoutEntry("series", stringResource(R.string.nav_series), false),
                            DashFlyoutEntry("radio", stringResource(R.string.nav_radio), false),
                        ),
                        active = "live",
                        entryFocus = flyoutFocus,
                        upTo = tvFocus,
                        downTo = iptvFocus,
                        onClose = {},
                        onChoose = { _ -> },
                    )
                }
                NavItem(
                    icon = NavIconStyle.Iptv,
                    label = stringResource(R.string.nav_iptv),
                    selected = showIptv,
                    onClick = {},
                    modifier = Modifier.focusRequester(iptvFocus),
                )
                if (showIptv) {
                    DashFlyout(
                        entries = listOf(
                            DashFlyoutEntry("live", stringResource(R.string.xtream_live), false),
                            DashFlyoutEntry("guide", stringResource(R.string.nav_guide), false),
                            DashFlyoutEntry("movies", stringResource(R.string.xtream_movies), false),
                            DashFlyoutEntry("series", stringResource(R.string.xtream_series), false),
                        ),
                        active = "live",
                        entryFocus = flyoutFocus,
                        upTo = iptvFocus,
                        downTo = FocusRequester.Cancel,
                        onClose = {},
                        onChoose = { _ -> },
                    )
                }
                NavItem(icon = NavIconStyle.Sport, label = stringResource(R.string.nav_sport), selected = false, onClick = {})
                NavItem(icon = NavIconStyle.Search, label = stringResource(R.string.nav_search), selected = false, onClick = {})
                NavItem(icon = NavIconStyle.MyItems, label = stringResource(R.string.nav_my_items), selected = false, onClick = {}, iconMotion = NavIconMotion.BOUNCE)
                NavItem(icon = NavIconStyle.Torrent, label = stringResource(R.string.nav_torrent), selected = false, onClick = {})
                Spacer(Modifier.weight(1f))
                NavItem(icon = NavIconStyle.Settings, label = stringResource(R.string.nav_settings), selected = false, onClick = {}, iconMotion = NavIconMotion.PULSE)
                Text(
                    "1.0.4  ·  build 142",
                    color = EnigmaColors.Subtle,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .skinPane()
                    .padding(start = 12.dp, top = 28.dp, end = 28.dp, bottom = 24.dp),
            ) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(com.enigmacast.ui.theme.ActiveSkin.current.id.name, color = EnigmaColors.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        repeat(4) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(140.dp)
                                    .background(EnigmaColors.Card, RoundedCornerShape(10.dp))
                                    .border(1.dp, EnigmaColors.CardBorder, RoundedCornerShape(10.dp)),
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun IptvProviderPreview(before: Boolean) {
        val provider = com.enigmacast.data.xtream.IptvProvider(
            id = "preview",
            name = "Demo IPTV",
            kind = com.enigmacast.domain.model.IptvPlaylistKind.XTREAM,
            server = "http://xtream.example:8080",
            username = "demo",
            password = "secret",
            enabled = true,
        )
        val accountLine = listOf(
            "Active",
            stringResource(R.string.iptv_account_days_left, 12, "1 Dec 2026"),
            stringResource(R.string.iptv_account_connections, 1, 3),
        ).joinToString("  ·  ")
        Box(Modifier.fillMaxSize().background(Color(0xFF101218))) {
            com.enigmacast.ui.xtream.IptvProviderEditorDialog(
                provider = provider,
                accountLine = accountLine,
                accountWarn = false,
                onSave = {},
                onClose = {},
                onToggleEnabled = {},
                onMoveUp = {},
                onMoveDown = {},
                onReload = {},
                onRequestDelete = {},
                twoColumn = !before,
            )
        }
    }

    private fun label(sec: Long): String =
        java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(java.time.Instant.ofEpochSecond(sec).atZone(java.time.ZoneId.systemDefault()))
}
