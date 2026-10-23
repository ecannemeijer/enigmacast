package com.enigmacast.data.xtream

import com.enigmacast.domain.model.IptvPlaylistKind
import com.enigmacast.domain.xtream.XtreamAccount
import kotlinx.serialization.Serializable

/**
 * One IPTV provider entered in Settings › IPTV. A provider is either a direct M3U URL or an
 * Xtream login. The list is kept in the encrypted SecretStore; the Room rows of each provider
 * use [sourceKey] so categories and channels can be loaded side by side.
 */
@Serializable
data class IptvProvider(
    val id: String = "",
    val name: String = "",
    val kind: IptvPlaylistKind = IptvPlaylistKind.M3U,
    val m3uUrl: String = "",
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
) {
    val login: IptvPlaylistLogin get() = IptvPlaylistLogin(m3uUrl, server, username, password)

    val sourceKey: String get() = sourceKey(id)

    fun configured(): Boolean = when (kind) {
        IptvPlaylistKind.M3U -> m3uUrl.startsWith("http://", true) || m3uUrl.startsWith("https://", true)
        IptvPlaylistKind.XTREAM -> login.xtreamAccount() != null
    }

    /** The account used by playback and EPG; for M3U the entry URLs are stored per channel. */
    fun account(): XtreamAccount? = when (kind) {
        IptvPlaylistKind.XTREAM -> login.xtreamAccount()?.copy(name = name.ifBlank { "IPTV" })
        IptvPlaylistKind.M3U -> XtreamAccount(
            name = name.ifBlank { "IPTV" },
            server = "http://m3u.invalid/$id",
            username = "",
            password = "",
            output = "ts",
        )
    }

    fun displayName(): String = name.ifBlank { if (kind == IptvPlaylistKind.M3U) "M3U" else "Xtream" }

    override fun toString(): String = "IptvProvider($id, $name, $kind, enabled=$enabled)"

    companion object {
        fun sourceKey(providerId: String): String = "provider:$providerId"
    }
}
