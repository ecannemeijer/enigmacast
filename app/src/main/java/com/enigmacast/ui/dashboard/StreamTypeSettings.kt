package com.enigmacast.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.network.StreamProfile
import com.enigmacast.domain.network.TranscodePreset
import com.enigmacast.ui.connection.ConnectionViewModel
import com.enigmacast.ui.theme.ChoiceRow
import com.enigmacast.ui.theme.TvFieldKind
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.SettingsCard
import com.enigmacast.ui.theme.TvButton
import com.enigmacast.util.asString
import com.enigmacast.util.isNotEmpty

/** Display order of the stream types: Normal, Transcoded, Automatic. */
private val StreamTypes = listOf(StreamProfile.DIRECT, StreamProfile.TRANSCODED, StreamProfile.AUTO)

/**
 * Settings › Playback › Stream type, for the active receiver: Normal (the original on port
 * 8001), Transcoded (the receiver's encoder, at home too) or Automatic (Normal at home,
 * transcoded away from home). Transcoded and Automatic show the size and the transcoding
 * port with a button that asks the receiver for it. Every change is saved at once.
 */
@Composable
internal fun StreamTypeCard(viewModel: ConnectionViewModel = hiltViewModel()) {
    val profile = viewModel.streamProfile
    SettingsCard(stringResource(R.string.stream_type_title)) {
        ChoiceRow(
            title = stringResource(R.string.stream_type_choice),
            description = stringResource(
                when (profile) {
                    StreamProfile.DIRECT -> R.string.stream_type_normal_desc
                    StreamProfile.TRANSCODED -> R.string.stream_type_transcoded_desc
                    StreamProfile.AUTO -> R.string.stream_type_auto_desc
                },
            ),
            options = listOf(
                stringResource(R.string.stream_type_normal),
                stringResource(R.string.stream_type_transcoded),
                stringResource(R.string.stream_type_auto),
            ),
            selectedIndex = StreamTypes.indexOf(profile),
            onSelect = { viewModel.chooseStreamProfile(StreamTypes[it]) },
        )
        if (profile != StreamProfile.DIRECT) {
            ChoiceRow(
                title = stringResource(R.string.stream_type_size),
                description = if (profile == StreamProfile.AUTO) stringResource(R.string.stream_type_size_auto) else null,
                options = TranscodePreset.entries.map { stringResource(R.string.quality_transcoded, it.height, it.bitrateKbps / 1000f) },
                selectedIndex = TranscodePreset.entries.indexOf(viewModel.transcodePreset),
                onSelect = { viewModel.chooseTranscodePreset(TranscodePreset.entries[it]) },
            )
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                CompactField(
                    label = stringResource(R.string.field_transcoding_port),
                    value = viewModel.transcodingPort,
                    onValueChange = viewModel::editTranscodingPort,
                    modifier = Modifier.width(140.dp),
                    kind = TvFieldKind.NUMBER,
                    followEdge = true,
                )
                TvButton(stringResource(R.string.stream_type_detect), viewModel::detectTranscodingPort, enabled = !viewModel.detecting)
            }
            Text(
                stringResource(R.string.stream_type_port_hint),
                color = EnigmaColors.Muted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        if (viewModel.streamTypeStatus.isNotEmpty()) {
            Text(
                viewModel.streamTypeStatus.asString(),
                color = EnigmaColors.Accent,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        Text(
            stringResource(R.string.stream_type_applies),
            color = EnigmaColors.Subtle,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
    }
}
