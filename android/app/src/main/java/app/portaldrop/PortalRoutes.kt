package app.portaldrop

import android.content.Context
import android.net.ConnectivityManager
import android.net.InetAddresses
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal sealed interface PortalRoute {
    data class Attached(val network: Network) : PortalRoute
    data class Hosted(val links: List<HotspotLink>) : PortalRoute {
        fun permits(address: InetAddress?): Boolean = links.any { it.permits(address) }
    }

    fun open(context: Context, url: URL): HttpsURLConnection = when (this) {
        is Attached -> network.openConnection(url, Proxy.NO_PROXY) as HttpsURLConnection
        is Hosted -> {
            if (!permits(portalAddress(url.host)) || isVpnActive(context)) {
                throw IOException("The computer is outside the attached hotspot.")
            }
            // Android does not always expose the downstream hotspot as a Network. The TLS
            // socket factory checks the kernel-selected source interface before sending HTTPS.
            url.openConnection(Proxy.NO_PROXY) as HttpsURLConnection
        }
    }
}

internal fun PortalRoute.matches(context: Context, host: String): Boolean {
    val address = portalAddress(host) ?: return false
    return when (this) {
        is PortalRoute.Attached -> context.getSystemService(ConnectivityManager::class.java)
            .getLinkProperties(network).hasLocalRoute(address)
        is PortalRoute.Hosted -> permits(address)
    }
}

internal data class HotspotLink(val name: String, val source: Inet4Address, val prefixLength: Int) {
    private val mask = -1 shl (32 - prefixLength)
    private val subnet = source.asIpv4Number() and mask

    fun permits(address: InetAddress?): Boolean =
        address is Inet4Address && address != source && address.isHotspotAddress() &&
            (address.asIpv4Number() and mask) == subnet

    fun owns(socket: Socket): Boolean =
        socket.localAddress == source && permits(socket.inetAddress) &&
            runCatching { NetworkInterface.getByInetAddress(source)?.name == name }.getOrDefault(false)
}

private fun Inet4Address.asIpv4Number(): Int =
    address.fold(0) { number, octet -> (number shl 8) or (octet.toInt() and 0xff) }

private fun Inet4Address.isHotspotAddress(): Boolean = isSiteLocalAddress || isLinkLocalAddress

internal fun portalAddress(host: String): InetAddress? =
    runCatching { InetAddresses.parseNumericAddress(host.removePrefix("[").removeSuffix("]")) }.getOrNull()
        ?.takeUnless { it.isLoopbackAddress || it.isMulticastAddress || it.isAnyLocalAddress }

internal fun isPortalNetwork(capabilities: NetworkCapabilities?): Boolean =
    capabilities?.let {
        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            (it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    } ?: false

internal fun LinkProperties?.hasLocalRoute(address: InetAddress): Boolean =
    this?.routes?.any { route ->
        !route.isDefaultRoute && !route.hasGateway() && route.destination.contains(address)
    } == true

@Suppress("DEPRECATION")
internal fun isVpnActive(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    return manager.allNetworks.any { network ->
        manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }
}

@Suppress("DEPRECATION") // All attached networks can be useful, including no-internet Wi-Fi.
internal fun portalRoutes(
    context: Context,
    host: String,
    allowHotspot: Boolean,
    requireLocal: Boolean = false,
    previousHost: String? = null,
): List<PortalRoute> {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val active = manager.activeNetwork
    val activeCapabilities = active?.let(manager::getNetworkCapabilities)
    val vpnActive = activeCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    val local = (listOfNotNull(active) + manager.allNetworks).distinct()
        .filter { isPortalNetwork(manager.getNetworkCapabilities(it)) }
    val address = portalAddress(host)
    val onLink = if (address == null) emptyList() else local.filter {
        manager.getLinkProperties(it).hasLocalRoute(address)
    }
    val previousAddress = previousHost?.let(::portalAddress)
    val previousOnLink = if (previousAddress == null) emptyList() else local.filter {
        it !in onLink && manager.getLinkProperties(it).hasLocalRoute(previousAddress)
    }
    val hosted = if (allowHotspot && !vpnActive && !isVpnActive(context)) {
        val attachedNames = local.mapNotNull { manager.getLinkProperties(it)?.interfaceName }.toSet()
        hotspotLinks(attachedNames).takeIf { it.isNotEmpty() }?.let { PortalRoute.Hosted(it) }
    } else null
    return buildList {
        addAll(onLink.map { PortalRoute.Attached(it) })
        if (hosted != null && (!requireLocal || hosted.permits(address))) add(hosted)
        if (!requireLocal) {
            addAll(previousOnLink.map { PortalRoute.Attached(it) })
            addAll((local - (onLink + previousOnLink).toSet()).map { PortalRoute.Attached(it) })
        }
        if (vpnActive && onLink.isNotEmpty()) add(PortalRoute.Attached(active))
    }
}

private val downstreamName = Regex("(?:ap_br_)?(?:wlan|wifi|swlan|ap|softap|wifi_br|br)[0-9]+")

private fun hotspotLinks(attachedNames: Set<String>): List<HotspotLink> = runCatching {
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().flatMap { iface ->
        if (!iface.isUp || iface.name in attachedNames || !downstreamName.matches(iface.name)) {
            return@flatMap emptyList()
        }
        iface.interfaceAddresses.mapNotNull { assigned ->
            val address = assigned.address as? Inet4Address ?: return@mapNotNull null
            val prefixLength = assigned.networkPrefixLength.toInt()
            if (!address.isHotspotAddress() || prefixLength !in 8..30) return@mapNotNull null
            HotspotLink(iface.name, address, prefixLength)
        }
    }.sortedWith(compareBy(HotspotLink::name, { it.source.hostAddress }))
}.getOrDefault(emptyList())
