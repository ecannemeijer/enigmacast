package com.enigmacast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.enigmacast.domain.model.PlayerState
import com.enigmacast.domain.player.PictureMode
import com.enigmacast.playback.TrackChoice
import com.enigmacast.playback.TrackMenu
import com.enigmacast.ui.player.VodOsdActions
import com.enigmacast.ui.player.VodOsdController
import com.enigmacast.ui.player.VodOsdModel
import com.enigmacast.ui.player.VodPlayerOsd
import com.enigmacast.ui.theme.EnigmaCastTheme
import com.enigmacast.util.UiText
import kotlinx.coroutines.delay

/** Debug-only: the VOD OSD over a sample frame, for screenshots (am start -n com.enigmacast/.OsdPreviewActivity). */
class OsdPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EnigmaCastTheme {
                val osd = remember { VodOsdController() }
                var playing by remember { mutableStateOf(true) }
                var position by remember { mutableStateOf(4_000L) }
                LaunchedEffect(Unit) { osd.show(); while (true) { delay(1_000); osd.touch() } }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF15171C), Color(0xFF0B0C10))))) {
                    Box(
                        Modifier.align(Alignment.TopCenter).offset(y = (-260).dp).size(620.dp)
                            .background(Brush.radialGradient(listOf(Color(0x00B8C6F0), Color(0x00B8C6F0), Color(0xFFB8C6F0), Color(0xFF8FA3D9))), CircleShape),
                    )
                    VodPlayerOsd(
                        controller = osd,
                        model = VodOsdModel(
                            title = "Häxan (1922)",
                            player = PlayerState(playbackState = "READY", playing = playing, positionMs = position, durationMs = 6_345_000, filePlayback = true,
                                videoWidth = 1440, videoHeight = 1080, videoCodec = "video/avc", audioLabel = UiText.Raw("English · AAC 2.0")),
                            tracks = TrackMenu(
                                audio = listOf(TrackChoice("a1", UiText.Raw("English · AAC 2.0"), true)),
                                subtitles = listOf(
                                    TrackChoice("s1", UiText.Raw("English"), true),
                                    TrackChoice("s2", UiText.Raw("Nederlands"), false),
                                    TrackChoice("ss:1", UiText.Raw("SubSource · Nederlands · Haxan.1922.Criterion.1080p"), false),
                                ),
                                subtitlesOn = true,
                            ),
                            pictureMode = PictureMode.AUTO,
                        ),
                        actions = VodOsdActions(
                            onTogglePause = { playing = !playing },
                            onSeek = { position = it },
                            onSubtitles = {}, onAudio = {}, onPictureMode = {}, onAudioDelay = {}, onSubtitleDelay = {},
                            onRestart = { position = 0 }, onSpeed = {}, onSleep = {},
                        ),
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}
