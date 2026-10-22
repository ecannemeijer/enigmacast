package com.enigmacast.companionbridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.enigmacast.util.EnigmaLog

/**
 * Adverteert de Companion-brug als `_enigmacast._tcp.` op het lokale netwerk. De telefoon
 * leest id, naam, app-versie en de TLS-pin uit de TXT-record, zodat hij meteen kan pinnen.
 */
class CompanionNsd(private val context: Context) {

    private var manager: NsdManager? = null
    private var listener: NsdManager.RegistrationListener? = null

    fun register(serviceName: String, id: String, appVersion: String, pin: String) {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) {
            EnigmaLog.service("NSD niet beschikbaar")
            Log.w(TAG, "NSD niet beschikbaar")
            return
        }
        unregister()
        manager = nsd
        val info = NsdServiceInfo().apply {
            setServiceName(serviceName.take(48))
            setServiceType(SERVICE_TYPE)
            setPort(CompanionProtocol.PORT)
            setAttribute("id", id)
            setAttribute("name", serviceName)
            setAttribute("app", appVersion)
            setAttribute("pin", pin)
        }
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                EnigmaLog.service("NSD geregistreerd: ${info.serviceName}")
                Log.i(TAG, "geregistreerd: ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                EnigmaLog.service("NSD registratie mislukt: $errorCode")
                Log.w(TAG, "registratie mislukt: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                EnigmaLog.service("NSD afgemeld")
                Log.i(TAG, "afgemeld")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                EnigmaLog.service("NSD afmelden mislukt: $errorCode")
                Log.w(TAG, "afmelden mislukt: $errorCode")
            }
        }
        listener = registration
        Log.i(TAG, "registreren: $serviceName")
        runCatching {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
        }.onFailure {
            EnigmaLog.service("NSD starten mislukt: ${it.javaClass.simpleName}")
            Log.w(TAG, "starten mislukt", it)
            manager = null
            listener = null
        }
    }

    fun unregister() {
        val nsd = manager
        val registration = listener
        manager = null
        listener = null
        if (nsd != null && registration != null) {
            runCatching { nsd.unregisterService(registration) }
        }
    }

    private companion object {
        const val TAG = "CompanionNsd"
        const val SERVICE_TYPE = "_enigmacast._tcp."
    }
}
