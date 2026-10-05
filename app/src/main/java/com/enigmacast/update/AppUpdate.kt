package com.enigmacast.update

import kotlinx.serialization.Serializable

const val UPDATE_MANIFEST_URL = "https://update.velixatv.com/enigmacast.json"
const val UPDATE_APK_URL = "https://update.velixatv.com/enigmacast.apk"

@Serializable
data class RemoteRelease(
    val versionCode: Int,
    val versionName: String = "",
    val apkUrl: String = UPDATE_APK_URL,
    val notes: String = "",
)

fun isNewerRelease(localVersionCode: Int, remote: RemoteRelease): Boolean {
    return remote.versionCode > localVersionCode
}
