package app.portaldrop

import android.content.Context
import android.util.Log
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import org.json.JSONArray
import org.json.JSONObject

internal class PortalHttpException(val status: Int, message: String) : IOException(message)
internal class TransferInterruptedException : IOException("The transfer was interrupted.")

internal data class PeerMeta(val os: String?, val name: String?, val protocolVersion: Int)

object PortalClient {
    internal const val DEFAULT_PORT = 53621

    fun pair(context: Context, link: String): Peer {
        val invite = PairingInvite.parse(link)
        val store = PortalStore.get(context)
        val routes = portalRoutes(context, invite.host, allowHotspot = true)
        if (routes.isEmpty()) throw IOException("Join Wi-Fi or start a hotspot to pair with your computer.")
        var peer = Peer(invite.host, invite.port, invite.fingerprint, store.pairingToken(invite.fingerprint))
        val hosted = try {
            val (meta, route) = tryRoutes(context, peer, routes) { session ->
                session.pair(invite.code)
            }
            peer = peer.copy(os = meta.os, name = meta.name)
            route is PortalRoute.Hosted
        } catch (error: PortalHttpException) {
            throw IOException(
                when (error.status) {
                    401, 403, 410 -> "This pairing link is expired or was refused. Create a new one on your computer."
                    409 -> "Your computer is already paired. Unpair it before adding another phone."
                    else -> "The computer refused pairing (HTTP ${error.status})."
                },
                error,
            )
        } catch (error: javax.net.ssl.SSLException) {
            throw IOException("Could not verify the computer certificate. Check the pairing link.", error)
        } catch (error: IOException) {
            throw IOException("Could not reach your computer. Check that both devices are on the same network.", error)
        }
        store.savePeer(peer)
        if (hosted) store.updateHotspotEndpoint(peer, peer.host, peer.port)
        TransferService.hotspotChanged()
        return peer
    }

    fun reconnect(context: Context, peer: Peer, address: String) {
        val parts = address.trim().split(':', limit = 2)
        val host = (portalAddress(parts.first()) as? Inet4Address)?.hostAddress
        val port = if (parts.size == 1) DEFAULT_PORT else parts[1].toIntOrNull()
        require(host != null && port != null && port in 1..65535) {
            "Enter the computer's IPv4 address and optional port."
        }
        val candidate = peer.copy(host = host, port = port)
        val localRoutes = portalRoutes(context, candidate.host, allowHotspot = true, requireLocal = true)
        val routes = if (localRoutes.any { it is PortalRoute.Hosted }) localRoutes else
            localRoutes + listOfNotNull(probeHostedRoute(context, host, port))
        if (routes.isEmpty()) throw IOException("Join the same Wi-Fi or hotspot as your computer.")
        val (_, route) = try {
            tryRoutes(context, candidate, routes) { it.verify() }
        } catch (error: PortalHttpException) {
            if (error.status == 401 || error.status == 403) {
                throw IOException("The desktop no longer accepts this pairing.", error)
            }
            throw IOException("The computer did not accept the connection. Check its service.", error)
        } catch (error: javax.net.ssl.SSLException) {
            throw IOException("This address did not match your paired computer.", error)
        } catch (error: IOException) {
            throw IOException("Could not reach your paired computer at this address.", error)
        }
        val store = PortalStore.get(context)
        val current = store.peer()
        check(current?.fingerprint == peer.fingerprint && current?.token == peer.token) {
            "The active paired computer has changed."
        }
        if (route is PortalRoute.Hosted) {
            store.updateHotspotEndpoint(peer, candidate.host, port)
        } else {
            store.updateEndpoint(peer, candidate.host, port)
        }
        TransferService.hotspotChanged()
    }

    private fun <T> tryRoutes(
        context: Context,
        peer: Peer,
        routes: List<PortalRoute>,
        attempt: (PortalSession) -> T,
    ): Pair<T, PortalRoute> {
        var last: IOException? = null
        for (route in routes) {
            val session = PortalSession(context, peer, route)
            try {
                return attempt(session) to route
            } catch (error: PortalHttpException) {
                throw error
            } catch (error: IOException) {
                if (last !is javax.net.ssl.SSLException) last = error
            } catch (error: SecurityException) {
                if (last == null) last = IOException("Android did not allow this network route.", error)
            } finally {
                session.cancel()
            }
        }
        throw last ?: IOException("No route to the computer.")
    }
}

internal fun InputStream.copyTransfer(
    output: OutputStream,
    start: Long,
    progress: (Long) -> Unit,
): Long {
    var copied = 0L
    val buffer = ByteArray(256 * 1024)
    while (true) {
        val count = read(buffer)
        if (count == -1) return copied
        output.write(buffer, 0, count)
        copied += count
        progress(start + copied)
    }
}

private class NoDelaySslSocketFactory(
    private val delegate: SSLSocketFactory,
    private val allowSocket: ((Socket) -> Boolean)?,
) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    private fun tune(socket: Socket): Socket = socket.apply {
        runCatching { tcpNoDelay = true }
    }

    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
        if (allowSocket?.invoke(s) == false) {
            runCatching { s.close() }
            throw IOException("The connection was routed outside the local hotspot.")
        }
        return tune(delegate.createSocket(s, host, port, autoClose))
    }
    private fun direct(create: () -> Socket): Socket {
        if (allowSocket != null) throw IOException("The local hotspot route could not be verified.")
        return tune(create())
    }
    override fun createSocket(host: String, port: Int): Socket =
        direct { delegate.createSocket(host, port) }
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        direct { delegate.createSocket(host, port, localHost, localPort) }
    override fun createSocket(host: InetAddress, port: Int): Socket =
        direct { delegate.createSocket(host, port) }
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        direct { delegate.createSocket(address, port, localAddress, localPort) }
}

internal class PortalSession(
    private val context: Context,
    private val peer: Peer,
    private val route: PortalRoute,
    private val onPeerMeta: ((PeerMeta) -> Unit)? = null,
) {
    private val activeConnections: MutableSet<HttpsURLConnection> =
        Collections.newSetFromMap(ConcurrentHashMap())
    private val eventConnection = AtomicReference<HttpsURLConnection?>()
    private data class TransferKey(val id: String, val incoming: Boolean)
    private val transferConnections = ConcurrentHashMap<TransferKey, HttpsURLConnection>()
    private val interruptedTransfers = ConcurrentHashMap.newKeySet<TransferKey>()
    @Volatile private var cancelled = false
    @Volatile var peerProtocolVersion: Int = 1
        private set

    private val localDeviceName = localAndroidDeviceName()

    private val socketFactory: SSLSocketFactory = NoDelaySslSocketFactory(
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

                override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
                    throw CertificateException("Unexpected client certificate.")
                }

                override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                    val leaf = chain.firstOrNull()
                    if (leaf == null || !isPinned(leaf)) {
                        throw CertificateException("The certificate does not match the paired device.")
                    }
                    leaf.checkValidity()
                }
            }), null)
        }.socketFactory,
        if (route is PortalRoute.Hosted) { socket ->
            socket.inetAddress == portalAddress(peer.host) &&
                socket.port == peer.port &&
                !isVpnActive(context) &&
                route.links.any { it.owns(context, socket) }
        } else null,
    )

    fun cancel() {
        cancelled = true
        val snapshot = activeConnections.toList()
        activeConnections.clear()
        for (connection in snapshot) {
            runCatching { connection.disconnect() }
        }
        eventConnection.getAndSet(null)?.disconnect()
    }

    fun interruptTransfer(id: String, incoming: Boolean): HttpsURLConnection? {
        val key = TransferKey(id, incoming)
        interruptedTransfers.add(key)
        return transferConnections[key]
    }

    fun allowTransfer(id: String, incoming: Boolean) {
        interruptedTransfers.remove(TransferKey(id, incoming))
    }

    fun pair(code: String): PeerMeta {
        val body = JSONObject()
            .put("code", code)
            .put("token", peer.token)
            .put("device_os", "android")
            .put("device_name", localDeviceName)
        return jsonRequest("POST", "/v1/pair", body, 204, authenticated = false) { http ->
            extractMeta(http)
        }
    }

    fun verify() {
        try {
            request("GET", "/v1/info", expectedStatus = 200) { }
        } catch (error: PortalHttpException) {
            if (error.status != 404 && error.status != 405) throw error
            request("GET", "/v1/outbox", expectedStatus = 200) { }
        }
    }

    fun outbox(): List<PortalItem> = request("GET", "/v1/outbox", expectedStatus = 200) {
        val values = JSONArray(it.controlBody(MAX_OUTBOX_BYTES))
        buildList {
            for (index in 0 until values.length()) {
                try {
                    add(portalItemFromJson(values.getJSONObject(index)))
                } catch (error: org.json.JSONException) {
                    Log.w("PortalDrop", "Skipped invalid item in the transfer queue.", error)
                } catch (error: IllegalArgumentException) {
                    Log.w("PortalDrop", "Skipped invalid item in the transfer queue.", error)
                }
            }
        }
    }

    fun chats(): List<ChatRoom> = request("GET", "/v1/chats", expectedStatus = 200) {
        val values = JSONArray(it.controlBody(MAX_OUTBOX_BYTES))
        buildList {
            for (index in 0 until values.length()) add(chatFromJson(values.getJSONObject(index)))
        }
    }

    fun publishChat(chat: ChatRoom) {
        jsonRequest("PUT", "/v1/chats/${chat.id}", chat.toJson(), 204) { }
    }

    fun acknowledge(id: String) {
        request("POST", "/v1/outbox/$id/ack", expectedStatus = 204) { }
    }

    fun transferStatus(item: PortalItem, incoming: Boolean): TransferState =
        request("GET", transferPath(item, incoming), expectedStatus = 200, response = ::readTransferState)

    fun controlTransfer(item: PortalItem, incoming: Boolean, action: TransferAction): TransferState =
        jsonRequest("POST", transferPath(item, incoming), JSONObject().put("action", action.wireName),
            200, response = ::readTransferState)

    private fun transferPath(item: PortalItem, incoming: Boolean): String {
        val desktopDirection = if (incoming) "sent" else "received"
        return "/v1/transfers/$desktopDirection/${item.id}"
    }

    private fun readTransferState(http: HttpsURLConnection): TransferState =
        transferState(JSONObject(http.controlBody(4 * 1024)).getString("state"))
            ?: throw IOException("The computer returned an unknown transfer state.")

    fun offer(item: PortalItem): Offer = jsonRequest("POST", "/v1/inbox", item.toJson(), 200) {
        val value = JSONObject(it.controlBody(4 * 1024))
        val offset = value.getLong("offset")
        require(offset in 0..(item.binarySize ?: 0L)) { "The computer returned an invalid transfer offset." }
        Offer(value.getBoolean("received"), offset)
    }

    fun upload(item: PortalItem, offset: Long, file: File, progress: (Long) -> Unit) {
        val size = checkNotNull(item.binarySize)
        if (!file.isFile || file.length() != size) throw IOException("The queued file is missing or incomplete.")
        uploadStream(item, offset, {
            file.inputStream().apply { channel.position(offset) }
        }, progress)
    }

    fun uploadStream(
        item: PortalItem,
        offset: Long,
        openAtOffset: () -> InputStream,
        progress: (Long) -> Unit,
    ) {
        val size = checkNotNull(item.binarySize)
        request("PUT", "/v1/inbox/${item.id}/file", "offset=$offset", 204,
            transferKey = TransferKey(item.id, incoming = false), output = { http ->
            http.doOutput = true
            http.setFixedLengthStreamingMode(size - offset)
            http.setRequestProperty("Content-Type", "application/octet-stream")
            openAtOffset().use { input ->
                BufferedOutputStream(http.outputStream, 64 * 1024).use { output ->
                    val copied = input.copyTransfer(output, offset, progress)
                    output.flush()
                    if (copied != size - offset) {
                        throw IOException("The file changed while uploading.")
                    }
                }
            }
        }) { }
    }

    fun download(item: PortalItem, offset: Long, save: (InputStream) -> Unit) {
        request("GET", "/v1/outbox/${item.id}/file", "offset=$offset", 200,
            transferKey = TransferKey(item.id, incoming = true)) { http ->
            val expected = checkNotNull(item.binarySize) - offset
            if (http.contentLengthLong != expected) throw IOException("The computer returned an incorrect file length.")
            http.inputStream.use(save)
        }
    }

    /**
     * Opens a long-lived Server-Sent Events stream (`/v1/events`) so messages and files
     * queued on desktop trigger instant delivery on Android instead of waiting for a poll.
     */
    fun streamEvents(onReady: () -> Unit, onSync: () -> Unit) {
        if (cancelled) return
        val url = URI("https", null, peer.host, peer.port, "/v1/events", null, null).toURL()
        val http = route.open(context, url)
        eventConnection.set(http)
        try {
            if (cancelled) return
            configureConnection(http, "GET", authenticated = true, readTimeoutMs = 45_000)
            http.setRequestProperty("Accept", "text/event-stream")
            val status = http.responseCode
            if (status != 200) throw PortalHttpException(status, "Event stream returned HTTP $status.")
            onPeerMeta?.invoke(extractMeta(http))
            onReady()
            BufferedReader(InputStreamReader(http.inputStream, Charsets.UTF_8), 512).use { reader ->
                var eventType = ""
                while (!cancelled) {
                    val line = reader.readLine() ?: break
                    when {
                        line.startsWith("event:") -> eventType = line.substringAfter("event:").trim()
                        line.startsWith("data:") -> {
                            if (eventType == "sync") {
                                onSync()
                                eventType = ""
                            }
                        }
                        line.isEmpty() -> {
                            if (eventType == "sync") onSync()
                            eventType = ""
                        }
                    }
                }
            }
        } finally {
            eventConnection.compareAndSet(http, null)
            http.disconnect()
        }
    }

    private fun <T> jsonRequest(
        method: String,
        path: String,
        body: JSONObject,
        expectedStatus: Int,
        authenticated: Boolean = true,
        response: (HttpsURLConnection) -> T,
    ): T {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        return request(method, path, expectedStatus = expectedStatus, authenticated = authenticated,
            output = {
                it.doOutput = true
                it.setFixedLengthStreamingMode(bytes.size)
                it.setRequestProperty("Content-Type", "application/json")
                it.outputStream.use { stream -> stream.write(bytes) }
            }, response = response)
    }

    private fun <T> request(
        method: String,
        path: String,
        query: String? = null,
        expectedStatus: Int,
        authenticated: Boolean = true,
        transferKey: TransferKey? = null,
        output: ((HttpsURLConnection) -> Unit)? = null,
        response: (HttpsURLConnection) -> T,
    ): T {
        if (cancelled) throw IOException("The connection is paused.")
        if (transferKey != null && transferKey in interruptedTransfers) throw TransferInterruptedException()
        val url = URI("https", null, peer.host, peer.port, path, query, null).toURL()
        val http = route.open(context, url)
        activeConnections.add(http)
        if (transferKey != null) transferConnections[transferKey] = http
        var failed = true
        try {
            if (cancelled) throw IOException("The connection is paused.")
            if (transferKey != null && transferKey in interruptedTransfers) throw TransferInterruptedException()
            configureConnection(http, method, authenticated, readTimeoutMs = 15_000)
            output?.invoke(http)
            val status = http.responseCode
            if (status != expectedStatus) {
                runCatching { http.errorStream?.close() }
                throw PortalHttpException(status, "Computer returned HTTP $status.")
            }
            onPeerMeta?.invoke(extractMeta(http))
            val result = response(http)
            runCatching { http.inputStream?.close() }
            failed = false
            return result
        } finally {
            if (transferKey != null) transferConnections.remove(transferKey, http)
            activeConnections.remove(http)
            // Keep healthy connections pooled for instant TLS keep-alive reuse; disconnect only on error/cancel.
            if (failed || cancelled) http.disconnect()
        }
    }

    private fun configureConnection(
        http: HttpsURLConnection,
        method: String,
        authenticated: Boolean,
        readTimeoutMs: Int,
    ) {
        http.requestMethod = method
        http.sslSocketFactory = socketFactory
        // The QR fingerprint is the endpoint's identity, even when its LAN address changes.
        http.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, tls ->
            val certificate = tls.peerCertificates.firstOrNull() as? X509Certificate
            certificate != null && isPinned(certificate)
        }
        http.connectTimeout = 4_000
        http.readTimeout = readTimeoutMs
        http.instanceFollowRedirects = false
        http.useCaches = false
        http.setRequestProperty("Connection", "keep-alive")
        http.setRequestProperty("Accept-Encoding", "identity")
        http.setRequestProperty("X-Portal-Drop-OS", "android")
        http.setRequestProperty("X-Portal-Drop-Name", localDeviceName)
        if (authenticated) {
            http.setRequestProperty("Authorization", "Bearer ${peer.token}")
            http.setRequestProperty("X-Portal-Drop-Version", "5")
        }
    }

    private fun extractMeta(http: HttpsURLConnection): PeerMeta {
        val os = http.getHeaderField("X-Portal-Drop-OS")?.trim()?.takeIf { it.isNotEmpty() }
        val name = http.getHeaderField("X-Portal-Drop-Name")?.trim()?.takeIf { it.isNotEmpty() }
        val version = http.getHeaderField("X-Portal-Drop-Version")?.trim()?.toIntOrNull() ?: 1
        if (version > peerProtocolVersion) peerProtocolVersion = version
        return PeerMeta(os, name, version)
    }

    private fun isPinned(certificate: X509Certificate): Boolean =
        MessageDigest.isEqual(
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded).hex().toByteArray(),
            peer.fingerprint.toByteArray(),
        )

    private fun HttpsURLConnection.controlBody(limit: Int): String = inputStream.use { stream ->
        val result = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val bytes = stream.read(buffer)
            if (bytes == -1) break
            if (result.size() + bytes > limit) throw IOException("The computer response is too large.")
            result.write(buffer, 0, bytes)
        }
        result.toString(Charsets.UTF_8.name())
    }

    data class Offer(val received: Boolean, val offset: Long)

    private companion object {
        const val MAX_OUTBOX_BYTES = 4 * 1024 * 1024
    }
}
