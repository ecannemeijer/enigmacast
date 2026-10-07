package com.enigmacast.data.network

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.enigmacast.R
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.util.EnigmaLog
import com.enigmacast.util.UiText
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

enum class TunnelState { OFF, CONNECTING, UP, NEEDS_CONSENT, FAILED }

data class TunnelStatus(val state: TunnelState = TunnelState.OFF, val detail: UiText = UiText.Empty)

/**
 * The experimental in-app WireGuard tunnel, using the official WireGuard library's userspace
 * backend ([GoBackend]) on Android's VpnService.
 *
 * The tunnel only carries EnigmaCast's own traffic (the config is limited to this app), comes
 * up when the router picks the tunnel route and goes down when home answers again or the app
 * exits. Android asks the user once to allow the VPN. Keys are never logged: errors log only
 * their type.
 */
@Singleton
class WireGuardTunnel @Inject constructor(
    @ApplicationContext private val context: Context,
) : TunnelControl {
    private val _status = MutableStateFlow(TunnelStatus())
    val status: StateFlow<TunnelStatus> = _status.asStateFlow()
    private val backend by lazy { GoBackend(context) }
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeConfig: Int? = null

    private val tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME

        override fun onStateChange(newState: Tunnel.State) {
            if (newState == Tunnel.State.DOWN && _status.value.state == TunnelState.UP) {
                activeConfig = null
                _status.value = TunnelStatus(TunnelState.OFF)
            }
        }
    }

    override fun enabledFor(config: ReceiverConfig): Boolean = config.wireGuard && config.wireGuardConfig.isNotBlank()

    /** The system dialog that lets the user allow the VPN, or null when it is allowed already. */
    fun consentIntent(): Intent? = runCatching { VpnService.prepare(context) }.getOrNull()

    override suspend fun up(config: ReceiverConfig): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) { bringUp(config) }
    }

    override suspend fun down() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                if (activeConfig != null || _status.value.state != TunnelState.OFF) {
                    runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
                    if (activeConfig != null) EnigmaLog.connection("wireguard down")
                }
                activeConfig = null
                _status.value = TunnelStatus(TunnelState.OFF)
            }
        }
    }

    /** Takes the tunnel down without waiting, for when the app exits. */
    fun shutdown() {
        if (activeConfig == null && _status.value.state == TunnelState.OFF) return
        scope.launch { down() }
    }

    private fun bringUp(config: ReceiverConfig): Boolean {
        if (!enabledFor(config)) return false
        val fingerprint = config.wireGuardConfig.hashCode()
        if (activeConfig == fingerprint && runCatching { backend.getState(tunnel) }.getOrNull() == Tunnel.State.UP) return true
        if (consentIntent() != null) {
            _status.value = TunnelStatus(TunnelState.NEEDS_CONSENT, UiText.res(R.string.wg_needs_consent))
            return false
        }
        _status.value = TunnelStatus(TunnelState.CONNECTING)
        val parsed = try {
            appOnly(Config.parse(config.wireGuardConfig.byteInputStream()))
        } catch (error: Exception) {
            // The parser's message can quote the bad value (a key), so only its type is logged.
            EnigmaLog.connection("wireguard config rejected (${error.javaClass.simpleName})")
            _status.value = TunnelStatus(TunnelState.FAILED, UiText.res(R.string.wg_config_invalid))
            return false
        }
        return try {
            backend.setState(tunnel, Tunnel.State.UP, parsed)
            activeConfig = fingerprint
            EnigmaLog.connection("wireguard up")
            _status.value = TunnelStatus(TunnelState.UP)
            true
        } catch (error: Exception) {
            val reason = (error as? BackendException)?.reason?.name ?: error.javaClass.simpleName
            EnigmaLog.connection("wireguard failed ($reason)")
            activeConfig = null
            _status.value = TunnelStatus(TunnelState.FAILED, UiText.res(R.string.wg_failed, reason))
            false
        }
    }

    /** The same tunnel, limited to EnigmaCast so other apps on the TV keep their own network. */
    private fun appOnly(config: Config): Config {
        val source = config.`interface`
        val builder = Interface.Builder()
            .setKeyPair(source.keyPair)
            .addAddresses(source.addresses)
            .addDnsServers(source.dnsServers)
            .addDnsSearchDomains(source.dnsSearchDomains)
            .includeApplication(context.packageName)
        source.listenPort.ifPresent { builder.setListenPort(it) }
        source.mtu.ifPresent { builder.setMtu(it) }
        return Config.Builder().setInterface(builder.build()).addPeers(config.peers).build()
    }

    private companion object {
        const val TUNNEL_NAME = "enigmacast"
    }
}
