package com.enigmacast.ui.osd

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.enigmacast.R
import com.enigmacast.domain.model.OsdInfoFields
import com.enigmacast.ui.theme.SettingsCard
import com.enigmacast.ui.theme.ToggleRow

/** Settings › OSD switches for the info strip. The skin still decides how a switched-on slot looks. */
@Composable
fun OsdInfoSettings(
    fields: OsdInfoFields,
    onChange: (OsdInfoFields) -> Unit,
) {
    SettingsCard(stringResource(R.string.osd_info_card)) {
        ToggleRow(
            title = stringResource(R.string.osd_info_signal),
            description = stringResource(R.string.osd_info_signal_desc),
            checked = fields.signal,
            onCheckedChange = { onChange(fields.copy(signal = it)) },
        )
        ToggleRow(
            title = stringResource(R.string.osd_info_satellite),
            description = stringResource(R.string.osd_info_satellite_desc),
            checked = fields.satellite,
            onCheckedChange = { onChange(fields.copy(satellite = it)) },
        )
        ToggleRow(
            title = stringResource(R.string.osd_info_encryption),
            description = stringResource(R.string.osd_info_encryption_desc),
            checked = fields.encryption,
            onCheckedChange = { onChange(fields.copy(encryption = it)) },
        )
        ToggleRow(
            title = stringResource(R.string.osd_info_tuner),
            checked = fields.tuner,
            onCheckedChange = { onChange(fields.copy(tuner = it)) },
        )
        ToggleRow(
            title = stringResource(R.string.osd_info_technical),
            description = stringResource(R.string.osd_info_technical_desc),
            checked = fields.technical,
            onCheckedChange = { onChange(fields.copy(technical = it)) },
        )
    }
}
