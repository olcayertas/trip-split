package com.olcayertas.tripsplit.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.olcayertas.tripsplit.core.lan.DiscoveredPeer
import com.olcayertas.tripsplit.core.lan.LAN_SERVICE_TYPE
import com.olcayertas.tripsplit.core.lan.PeerDiscovery
import com.olcayertas.tripsplit.core.lan.parseTxt
import java.net.Inet4Address

/**
 * DNS-SD with `NsdManager` (SPEC §10.1.1 and §10.1.2). It registers `_tripsplit._tcp` and browses for it.
 * NsdManager resolves one service at a time, so found services wait in a queue.
 */
@Suppress("DEPRECATION")
class NsdDiscovery(context: Context) : PeerDiscovery {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var multicast: WifiManager.MulticastLock? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var ownName = ""
    private var onPeer: ((DiscoveredPeer) -> Unit)? = null
    private val lock = Any()

    override fun start(port: Int, txt: Map<String, String>, instance: String, onPeer: (DiscoveredPeer) -> Unit) {
        this.onPeer = onPeer
        ownName = instance
        multicast = wifi.createMulticastLock("tripsplit-nsd").apply { setReferenceCounted(false); acquire() }
        val info = NsdServiceInfo().apply {
            serviceName = instance
            serviceType = LAN_SERVICE_TYPE
            setPort(port)
            for ((k, v) in txt) setAttribute(k, v)
        }
        val reg = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
        }
        registration = reg
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg)
        val disc = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // The name of this device can come back with a suffix. The director ignores our own deviceId.
                synchronized(lock) { queue.addLast(serviceInfo) }
                resolveNext()
            }
        }
        discovery = disc
        nsd.discoverServices(LAN_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, disc)
    }

    private fun resolveNext() {
        val next = synchronized(lock) {
            if (resolving) return
            val n = queue.removeFirstOrNull() ?: return
            resolving = true
            n
        }
        nsd.resolveService(
            next,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    synchronized(lock) { resolving = false }
                    resolveNext()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host?.hostAddress
                    val record = parseTxt(serviceInfo.attributes)
                    if (host != null && record != null && record.version >= 1) {
                        onPeer?.invoke(DiscoveredPeer(host, serviceInfo.port, record))
                    }
                    synchronized(lock) { resolving = false }
                    resolveNext()
                }
            },
        )
    }

    override fun stop() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        registration?.let { runCatching { nsd.unregisterService(it) } }
        discovery = null
        registration = null
        onPeer = null
        synchronized(lock) { queue.clear(); resolving = false }
        multicast?.let { if (it.isHeld) it.release() }
        multicast = null
    }
}

/** The IPv4 address of the default gateway of the active network (SPEC §10.1.2 step 4), or null. */
fun defaultGateway(context: Context): String? {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return null
    val props = cm.getLinkProperties(network) ?: return null
    return props.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
}
