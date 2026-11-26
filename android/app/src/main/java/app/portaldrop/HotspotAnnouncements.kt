package app.portaldrop

import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Accepts paired discovery hints from the phone's downstream subnet during hotspot sharing. */
internal class HotspotAnnouncements {
    private data class ListenKey(
        val fingerprint: String,
        val token: String,
        val route: PortalRoute.Hosted,
        val generation: Int,
        val allowLegacy: Boolean,
    )

    private var current: ListenKey? = null
    private var listener: DatagramSocket? = null

    @Synchronized
    fun update(
        peer: Peer,
        route: PortalRoute.Hosted?,
        generation: Int,
        allowLegacy: Boolean,
        found: (String, Int, Boolean) -> Unit,
    ) {
        val key = route?.let { ListenKey(peer.fingerprint, peer.token, it, generation, allowLegacy) }
        if (current == key && listener?.isClosed == false) return
        stop()
        if (key == null) return
        val socket = runCatching { DatagramSocket(null) }.getOrNull() ?: return
        // Directed broadcast requires a wildcard bind. The subnet guard and MAC below
        // restrict which packets may schedule a connection attempt.
        if (runCatching { socket.bind(InetSocketAddress(PORT)) }.isFailure) {
            socket.close()
            return
        }
        listener = socket
        current = key
        Thread({ listen(socket, key, found) }, "Portal Drop hotspot discovery").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        current = null
        listener?.close()
        listener = null
    }

    private fun listen(socket: DatagramSocket, key: ListenKey, found: (String, Int, Boolean) -> Unit) {
        val buffer = ByteArray(128)
        val packet = DatagramPacket(buffer, buffer.size)
        val mac = Mac.getInstance("HmacSHA256").apply {
            val storedKey = MessageDigest.getInstance("SHA-256").digest(key.token.toByteArray(Charsets.US_ASCII))
            init(SecretKeySpec(storedKey, algorithm))
        }
        val legacyPrefix = "PORTALDROP-HOTSPOT/1 ${key.fingerprint} "
        var lastAccepted = -1_000L
        socket.use {
            while (!socket.isClosed) {
                packet.length = buffer.size
                if (runCatching { socket.receive(packet) }.isFailure) break
                val now = SystemClock.elapsedRealtime()
                if (now - lastAccepted < 1_000 || !key.route.permits(packet.address)) continue
                val payload = String(buffer, packet.offset, packet.length, Charsets.US_ASCII)
                val host = packet.address.hostAddress ?: continue
                val signed = payload.startsWith("$PROTOCOL ")
                val port = when {
                    signed -> authenticatedPort(payload, host, mac)
                    key.allowLegacy && payload.startsWith(legacyPrefix) && payload.endsWith('\n') ->
                        parsePort(payload.substring(legacyPrefix.length, payload.lastIndex))
                    else -> null
                } ?: continue
                if (socket.isClosed) break
                lastAccepted = now
                found(host, port, signed)
            }
        }
    }

    private fun authenticatedPort(payload: String, host: String, mac: Mac): Int? {
        if (!payload.endsWith('\n')) return null
        val fields = payload.dropLast(1).split(' ')
        if (fields.size != 4 || fields[0] != PROTOCOL) return null
        val (_, port, time, signature) = fields
        val parsedPort = parsePort(port) ?: return null
        if (time.length !in 1..13 || time.any { it !in '0'..'9' }) return null
        val seconds = time.toLong()
        val now = System.currentTimeMillis() / 1_000
        if (seconds !in (now - MAX_AGE_SECONDS)..(now + MAX_AGE_SECONDS)) return null
        if (signature.length != 64 || signature.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        val received = ByteArray(32) { byte -> signature.substring(byte * 2, byte * 2 + 2).toInt(16).toByte() }
        val expected = mac.doFinal("$PROTOCOL $host $port $time".toByteArray(Charsets.US_ASCII))
        return parsedPort.takeIf { MessageDigest.isEqual(received, expected) }
    }

    private fun parsePort(text: String): Int? =
        text.takeIf { it.length in 1..5 && it.all { c -> c in '0'..'9' } }?.toInt()?.takeIf { it in 1..65535 }

    private companion object {
        const val PROTOCOL = "PORTALDROP-HOTSPOT/2"
        const val MAX_AGE_SECONDS = 90L
        const val PORT = 53622
    }
}
