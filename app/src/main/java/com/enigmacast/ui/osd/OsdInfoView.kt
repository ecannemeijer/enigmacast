package com.enigmacast.ui.osd

import com.enigmacast.R
import com.enigmacast.domain.model.OsdInfoFields
import com.enigmacast.domain.osdinfo.CryptState
import com.enigmacast.domain.osdinfo.OsdInfo
import com.enigmacast.domain.osdinfo.SignalInfo
import com.enigmacast.util.UiText
import java.util.Locale

/** Colour of the encryption badge. The skin chooses the paint; the presenter only names the tone. */
enum class CryptTone { FREE, OPEN, LOCKED, PROBLEM }

/** Signal bars for one reading. Percents are 0–100; null means the receiver did not report that value. */
data class SignalBars(
    val strengthPercent: Int?,
    val qualityPercent: Int?,
    /** SNR in dB when the receiver gave no quality percent, e.g. "12.4 dB". */
    val qualityDb: String?,
)

/** Encryption badge. [systems] is a raw CA-system name such as "Nagravision", or null. */
data class CryptBadge(
    val text: UiText,
    val tone: CryptTone,
    val systems: String? = null,
)

/**
 * What a skin draws for the channel-banner info strip. Built from [OsdInfo] and the
 * Settings › OSD switches. A skin places these slots and does not decide what they mean.
 */
data class OsdInfoView(
    val signal: SignalBars? = null,
    val satellite: String? = null,
    val crypt: CryptBadge? = null,
    val tuner: UiText? = null,
    val technical: String? = null,
) {
    val isEmpty: Boolean
        get() = signal == null && satellite == null && crypt == null && tuner == null && technical.isNullOrBlank()
}

/** Turns receiver data into the slots a skin draws. Hidden switches and unknown values leave a slot empty. */
object OsdInfoPresenter {
    fun present(info: OsdInfo, fields: OsdInfoFields): OsdInfoView = OsdInfoView(
        signal = if (fields.signal) signal(info) else null,
        satellite = if (fields.satellite) satellite(info) else null,
        crypt = if (fields.encryption) crypt(info) else null,
        tuner = if (fields.tuner) tuner(info) else null,
        technical = if (fields.technical) info.transponder?.technicalLine?.takeIf { it.isNotBlank() } else null,
    )

    private fun signal(info: OsdInfo): SignalBars? {
        val reading = (info.signal as? SignalInfo.Measured)?.reading ?: return null
        val strength = reading.agcPercent?.coerceIn(0, 100)
        val quality = reading.snrPercent?.coerceIn(0, 100)
        val db = if (quality == null) reading.snrDb?.let { String.format(Locale.US, "%.1f dB", it) } else null
        if (strength == null && quality == null && db == null) return null
        return SignalBars(strength, quality, db)
    }

    private fun satellite(info: OsdInfo): String? {
        val orbital = info.orbital ?: return null
        val name = orbital.satelliteName
        return if (name.isNullOrBlank()) orbital.label else "${orbital.label} · $name"
    }

    private fun crypt(info: OsdInfo): CryptBadge? {
        val systems = info.crypt.systems.takeIf { it.isNotEmpty() }?.joinToString(", ")
        return when (info.crypt.state) {
            CryptState.FREE -> CryptBadge(UiText.res(R.string.osd_crypt_free), CryptTone.FREE)
            CryptState.DECRYPTED -> CryptBadge(UiText.res(R.string.osd_crypt_decrypted), CryptTone.OPEN, systems)
            CryptState.ENCRYPTED -> CryptBadge(UiText.res(R.string.osd_crypt_encrypted), CryptTone.LOCKED, systems)
            CryptState.NOT_DECRYPTED -> CryptBadge(UiText.res(R.string.osd_crypt_not_decrypted), CryptTone.PROBLEM, systems)
            CryptState.UNKNOWN, CryptState.NOT_APPLICABLE -> null
        }
    }

    private fun tuner(info: OsdInfo): UiText? {
        val tuner = info.tuner ?: return null
        val letter = tuner.letter ?: return null
        val type = tuner.type?.takeIf { it.isNotBlank() }
        return if (type == null) UiText.res(R.string.osd_tuner, letter) else UiText.res(R.string.osd_tuner_type, letter, type)
    }
}
