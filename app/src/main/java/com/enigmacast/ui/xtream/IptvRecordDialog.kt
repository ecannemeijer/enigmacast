package com.enigmacast.ui.xtream

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.enigmacast.R
import com.enigmacast.ui.theme.TvChoiceDialog

/** Warns when a new IPTV capture may exceed the provider's max_connections. */
@Composable
fun IptvRecordAskDialog(model: XtreamViewModel) {
    val ask = model.iptvRecordAsk ?: return
    val max = ask.maxConnections ?: 0
    val used = ask.usedConnections ?: 0
    TvChoiceDialog(
        title = stringResource(R.string.iptv_record_connection_title),
        message = stringResource(R.string.iptv_record_connection_message, max, used),
        choices = listOf(
            stringResource(R.string.iptv_record_anyway),
            stringResource(R.string.cancel),
        ),
        stacked = true,
        onChoose = { index ->
            if (index == 0) model.confirmIptvRecord() else model.dismissIptvRecord()
        },
        onDismiss = model::dismissIptvRecord,
    )
}
