package app.portaldrop

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address

internal class PortalDiscovery(private val context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var scan: Scan? = null

    fun find(peer: Peer, routes: List<PortalRoute>, found: (List<Pair<PortalRoute, String>>, Int) -> Unit) {
        main.post {
            if (scan != null) return@post
            val localRoutes = routes.filter {
                it !is PortalRoute.Attached || isPortalNetwork(connectivity.getNetworkCapabilities(it.network))
            }
            if (localRoutes.isEmpty()) return@post
            val next = Scan(peer, localRoutes, found)
            scan = next
            next.start()
        }
    }

    fun stop() {
        main.post { scan?.finish() }
    }

    @Suppress("DEPRECATION")
    private inner class Scan(
        private val peer: Peer,
        private val routes: List<PortalRoute>,
        private val found: (List<Pair<PortalRoute, String>>, Int) -> Unit,
    ) : NsdManager.DiscoveryListener {
        private val timeout = Runnable(::finish)
        private val pending = ArrayDeque<NsdServiceInfo>()
        private val seen = mutableSetOf<String>()
        private var activeResolve: NsdManager.ResolveListener? = null
        private var multicastLock: WifiManager.MulticastLock? = null

        fun start() {
            try {
                val network = (routes.singleOrNull() as? PortalRoute.Attached)?.network
                if (Build.VERSION.SDK_INT < 33 || network == null) {
                    val wifi = context.getSystemService(WifiManager::class.java)
                    multicastLock = wifi?.createMulticastLock("Portal Drop discovery")?.apply { acquire() }
                }
                if (Build.VERSION.SDK_INT >= 33 && network != null) {
                    nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, network,
                        context.mainExecutor, this)
                } else {
                    nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, this)
                }
                main.postDelayed(timeout, 8_000)
            } catch (_: RuntimeException) {
                finish()
            }
        }

        fun finish() {
            if (scan !== this) return
            scan = null
            main.removeCallbacks(timeout)
            if (Build.VERSION.SDK_INT >= 34) {
                activeResolve?.let { runCatching { nsd.stopServiceResolution(it) } }
            }
            runCatching { nsd.stopServiceDiscovery(this) }
            multicastLock?.let { if (it.isHeld) it.release() }
        }

        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) { main.post(::finish) }
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { main.post(::finish) }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { main.post(::finish) }
        override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

        override fun onServiceFound(info: NsdServiceInfo) {
            main.post {
                if (scan !== this || pending.size >= 32 || !seen.add(info.serviceName)) return@post
                pending.addLast(info)
                resolveNext()
            }
        }

        private fun resolveNext() {
            if (activeResolve != null || pending.isEmpty()) return
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    main.post { resolved(null) }
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    main.post { resolved(info) }
                }
            }
            activeResolve = listener
            try {
                nsd.resolveService(pending.removeFirst(), listener)
            } catch (_: RuntimeException) {
                activeResolve = null
            }
        }

        private fun resolved(info: NsdServiceInfo?) {
            if (scan !== this) return
            activeResolve = null
            val matches = info?.attributes?.get("fp")?.toString(Charsets.UTF_8) == peer.fingerprint
            if (info != null && matches && info.port in 1..65535) {
                val addresses = if (Build.VERSION.SDK_INT >= 34) {
                    info.hostAddresses
                } else {
                    listOfNotNull(info.host)
                }
                val ordered = addresses
                    .filter { !it.isLoopbackAddress && !it.isMulticastAddress && !it.isAnyLocalAddress }
                    .sortedByDescending { it is Inet4Address }
                val candidates = routes.flatMap { route ->
                    val properties = (route as? PortalRoute.Attached)?.let { connectivity.getLinkProperties(it.network) }
                    ordered.filter { address ->
                        when (route) {
                            is PortalRoute.Attached -> properties.hasLocalRoute(address)
                            is PortalRoute.Hosted -> route.permits(address)
                        }
                    }.mapNotNull { address -> address.hostAddress?.let { route to it } }
                }.distinct().take(8)
                if (candidates.isNotEmpty()) {
                    finish()
                    found(candidates, info.port)
                    return
                }
            }
            resolveNext()
        }
    }

    private companion object {
        const val SERVICE_TYPE = "_portaldrop._tcp."
    }
}
