package com.enigmacast

import android.os.Bundle
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = runCatching { OsdSkinId.valueOf(intent.getStringExtra("skin").orEmpty()) }.getOrDefault(OsdSkinId.STANDARD)
        val mode = intent.getStringExtra("mode").orEmpty().lowercase()
        ActiveSkin.apply(id)
        val now = System.currentTimeMillis() / 1000
        val start = now - 25 * 60
        val events = listOf(
            EpgEvent("NOS Journaal", "", "Het laatste nieuws uit binnen- en buitenland.", label(start), label(start + 45 * 60), start, 45 * 60, true),
            EpgEvent("Studio Sport Eredivisie", "", "", label(start + 45 * 60), label(start + 105 * 60), start + 45 * 60, 60 * 60, false),
        )
        setContent {
            EnigmaCastTheme {
                when (mode) {
                    "list" -> PliListPreview(now)
                    "epg" -> PliEpgPreview(now, events)
                    else -> DefaultPreview(id, events, start)
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
                                Text("%04d".format(num), color = if (focused) PliHd.SecondFg else PliHd.Foreground, fontSize = 13.sp, modifier = Modifier.width(48.dp))
                                Text(name, color = if (focused) PliHd.SecondFg else PliHd.Foreground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.4f), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                            Text(event.title, color = if (focused) PliHd.SecondFg else PliHd.Foreground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
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

    private fun label(sec: Long): String =
        java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(java.time.Instant.ofEpochSecond(sec).atZone(java.time.ZoneId.systemDefault()))
}
