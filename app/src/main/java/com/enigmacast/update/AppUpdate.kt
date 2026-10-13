package com.enigmacast.update

import kotlinx.serialization.Serializable

/**
 * Builds from the accounts release on read enigmacast-v2.json. enigmacast.json stays frozen for
 * older installs and only tells them to reinstall (its apkUrl is not an APK).
 */
const val UPDATE_MANIFEST_URL = "https://update.velixatv.com/enigmacast-v2.json"
const val UPDATE_APK_URL = "https://update.velixatv.com/enigmacast.apk"

/**
 * dist/enigmacast-v2.json (dist/enigmacast.json for pre-account builds). [reinstallRequired] marks a release that cannot be installed over the
 * running version: the dialog then explains why (in [reinstallMessage] / [reinstallMessageNl],
 * or a built-in text) and offers only OK. Builds that predate the field ignore it and show [notes].
 */
@Serializable
data class RemoteRelease(
    val versionCode: Int,
    val versionName: String = "",
    val apkUrl: String = UPDATE_APK_URL,
    val notes: String = "",
    val reinstallRequired: Boolean = false,
    val reinstallMessage: String = "",
    val reinstallMessageNl: String = "",
)

fun isNewerRelease(localVersionCode: Int, remote: RemoteRelease): Boolean {
    return remote.versionCode > localVersionCode
}

/** The reinstall text in the app language ("nl" or anything else = English); blank means use the built-in text. */
fun RemoteRelease.reinstallText(language: String): String {
    val nl = language.lowercase().startsWith("nl")
    return if (nl) reinstallMessageNl.ifBlank { reinstallMessage } else reinstallMessage.ifBlank { reinstallMessageNl }
}
