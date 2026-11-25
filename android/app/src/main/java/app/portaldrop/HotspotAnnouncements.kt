package app.portaldrop

import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/** Listens only on the phone's downstream address while a Portal Drop hotspot is running. */
internal class HotspotAnnouncements {
    private data class ListenKey(val fingerprint: String, val route: PortalRoute.Hosted, val generation: Int)

    private var current: ListenKey? = null
    private val sockets = mutableListOf<DatagramSocket>()

    @Synchronized
    fun update(
        fingerprint: String,
        route: PortalRoute.Hosted?,
        generation: Int,
        found: (String, Int) -> Unit,
    ) {
        val key = route?.let { ListenKey(fingerprint, it, generation) }
        if (current == key) return
        stop()
        if (key == null) return
        current = key
        for (link in key.route.links) {
            val socket = runCatching { DatagramSocket(null) }.getOrNull() ?: continue
            val bound = runCatching { socket.bind(InetSocketAddress(link.source, PORT)) }.isSuccess
            if (!bound) {
                socket.close()
                continue
            }
            sockets.add(socket)
            Thread({ listen(socket, link, key.fingerprint, found) }, "Portal Drop hotspot discovery").apply {
                isDaemon = true
                start()
            }
        }
        if (sockets.isEmpty()) current = null
    }

    @Synchronized
    fun stop() {
        current = null
        sockets.forEach(DatagramSocket::close)
        sockets.clear()
    }

    private fun listen(socket: DatagramSocket, link: HotspotLink, fingerprint: String, found: (String, Int) -> Unit) {
        val buffer = ByteArray(96)
        val packet = DatagramPacket(buffer, buffer.size)
        val prefix = "PORTALDROP-HOTSPOT/1 $fingerprint "
        var lastAccepted = -1_000L
        socket.use {
            while (!socket.isClosed) {
                packet.length = buffer.size
                if (runCatching { socket.receive(packet) }.isFailure) break
                val now = SystemClock.elapsedRealtime()
                if (now - lastAccepted < 1_000 || !link.permits(packet.address)) continue
                val payload = String(buffer, packet.offset, packet.length, Charsets.US_ASCII)
                if (!payload.startsWith(prefix) || !payload.endsWith('\n')) continue
                val portText = payload.substring(prefix.length, payload.lastIndex)
                if (portText.length !in 1..5 || portText.any { it !in '0'..'9' }) continue
                val port = portText.toInt()
                if (port !in 1..65535 || socket.isClosed) continue
                val host = packet.address.hostAddress ?: continue
                lastAccepted = now
                found(host, port)
            }
        }
    }

    private companion object {
        const val PORT = 53622
    }
}
