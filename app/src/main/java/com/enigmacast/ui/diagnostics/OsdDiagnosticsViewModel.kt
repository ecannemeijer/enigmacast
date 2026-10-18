package com.enigmacast.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.data.openwebif.OpenWebifApi
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.osd.GrabFormat
import com.enigmacast.domain.osd.GrabMode
import com.enigmacast.domain.osd.PngInspector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GrabDiagnostic(
    val label: String,
    val status: String,
    val contentType: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Int,
    val durationMs: Long,
    val alpha: Boolean,
    val bytes: ByteArray?,
)

@HiltViewModel
class OsdDiagnosticsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val api: OpenWebifApi,
) : ViewModel() {
    private val _result = MutableStateFlow<GrabDiagnostic?>(null)
    val result: StateFlow<GrabDiagnostic?> = _result.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun grab(format: GrabFormat, mode: GrabMode) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val config = settingsRepository.current().activeReceiver()
            if (config == null) {
                _result.value = GrabDiagnostic("No receiver", "Not configured", "", 0, 0, 0, 0, false, null)
                _busy.value = false
                return@launch
            }
            val label = "${format.wire.uppercase()} ${mode.wire.uppercase()}"
            _result.value = try {
                val image = api.grab(config, format, mode)
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    android.graphics.BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, bounds)
                }
                GrabDiagnostic(
                    label = label,
                    status = image.httpStatus.toString(),
                    contentType = image.contentType.orEmpty(),
                    width = bounds.outWidth,
                    height = bounds.outHeight,
                    sizeBytes = image.bytes.size,
                    durationMs = image.durationMs,
                    alpha = PngInspector.hasAlphaChannel(image.bytes),
                    bytes = image.bytes,
                )
            } catch (error: Exception) {
                GrabDiagnostic(label, error.message ?: "Failed", "", 0, 0, 0, 0, false, null)
            }
            _busy.value = false
        }
    }
}
