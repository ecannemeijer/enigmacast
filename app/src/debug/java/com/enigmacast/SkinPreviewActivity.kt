package com.enigmacast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
        ActiveSkin.apply(id)
        val now = System.currentTimeMillis() / 1000
        val start = now - 25 * 60
        val events = listOf(
            EpgEvent("NOS Journaal", "", "Het laatste nieuws uit binnen- en buitenland.", label(start), label(start + 45 * 60), start, 45 * 60, true),
            EpgEvent("Studio Sport Eredivisie", "", "", label(start + 45 * 60), label(start + 105 * 60), start + 45 * 60, 60 * 60, false),
        )
        setContent {
            EnigmaCastTheme {
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
        }
    }

    private fun label(sec: Long): String =
        java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(java.time.Instant.ofEpochSecond(sec).atZone(java.time.ZoneId.systemDefault()))
}
