package com.enigmacast.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.enigmacast.R
import com.enigmacast.domain.osd.GrabFormat
import com.enigmacast.domain.osd.GrabMode
import com.enigmacast.ui.theme.TvButton

@Composable
fun OsdDiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: OsdDiagnosticsViewModel = hiltViewModel(),
) {
    val result by viewModel.result.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.osd_diagnostics), color = Color.White, fontSize = 36.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(stringResource(R.string.diag_grab, "PNG", "OSD"), { viewModel.grab(GrabFormat.PNG, GrabMode.OSD) }, enabled = !busy)
            TvButton(stringResource(R.string.diag_grab, "JPG", "OSD"), { viewModel.grab(GrabFormat.JPG, GrabMode.OSD) }, enabled = !busy)
            TvButton(stringResource(R.string.diag_grab, "PNG", "ALL"), { viewModel.grab(GrabFormat.PNG, GrabMode.ALL) }, enabled = !busy)
            TvButton(stringResource(R.string.diag_grab, "JPG", "ALL"), { viewModel.grab(GrabFormat.JPG, GrabMode.ALL) }, enabled = !busy)
            TvButton(stringResource(R.string.back), onBack)
        }
        result?.let { grab ->
            Text(stringResource(R.string.diag_request, grab.label), color = Color.White, fontSize = 20.sp)
            Text(stringResource(R.string.diag_http_status, grab.status), color = Color.White, fontSize = 20.sp)
            Text(stringResource(R.string.diag_content_type, grab.contentType), color = Color.White, fontSize = 20.sp)
            Text(stringResource(R.string.diag_resolution, grab.width, grab.height), color = Color.White, fontSize = 20.sp)
            Text(stringResource(R.string.diag_size, grab.sizeBytes), color = Color.White, fontSize = 20.sp)
            Text(stringResource(R.string.diag_duration, grab.durationMs), color = Color.White, fontSize = 20.sp)
            Text(stringResource(R.string.diag_alpha, stringResource(R.string.yes).takeIf { grab.alpha } ?: stringResource(R.string.no_lower)), color = Color.White, fontSize = 20.sp)
            grab.bytes?.let { bytes ->
                AsyncImage(
                    model = ImageRequest.Builder(context).data(bytes).build(),
                    contentDescription = stringResource(R.string.diag_grabbed),
                    modifier = Modifier.fillMaxWidth().height(420.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}
