package app.portaldrop

import android.content.Context
import android.net.ConnectivityManager
import android.net.InetAddresses
import android.net.Network
import android.net.NetworkCapabilities
import android.net.LinkProperties
import android.util.Log
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.Proxy
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
    fun pair(context: Context, link: String): Peer {
        val invite = PairingInvite.parse(link)
        val store = PortalStore.get(context)
        val networks = portalNetworks(context, invite.host)
        if (networks.isEmpty()) throw IOException("Connect to Wi-Fi to pair with your computer.")
        var peer = Peer(invite.host, invite.port, invite.fingerprint, store.pairingToken(invite.fingerprint))
        try {
            var last: IOException? = null
            for (network in networks) {
                try {
                    val meta = PortalSession(peer, network).pair(invite.code)
                    peer = peer.copy(os = meta.os, name = meta.name)
                    last = null
                    break
                } catch (error: PortalHttpException) {
                    throw error
                } catch (error: IOException) {
                    last = error
                } catch (error: SecurityException) {
                    last = IOException("Android did not allow this network route.", error)
                }
            }
            last?.let { throw it }
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
        return peer
    }
}

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

internal fun portalNetworks(context: Context, host: String): List<Network> {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val active = manager.activeNetwork
    @Suppress("DEPRECATION")
    val local = manager.allNetworks.filter { isPortalNetwork(manager.getNetworkCapabilities(it)) }
    val activeCapabilities = active?.let(manager::getNetworkCapabilities)
    return buildList {
        if (active != null && isPortalNetwork(activeCapabilities)) add(active)
        addAll(local)
        // Only try the system VPN route to a host that is on a directly attached LAN.
        if (active != null && activeCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
            stillOnLocalSubnet(manager, local, host)) add(active)
    }.distinct()
}

private fun stillOnLocalSubnet(manager: ConnectivityManager, networks: List<Network>, host: String): Boolean {
    val address = try {
        InetAddresses.parseNumericAddress(host.removePrefix("[").removeSuffix("]"))
    } catch (_: IllegalArgumentException) {
        return false
    }
    if (address.isLoopbackAddress || address.isMulticastAddress || address.isAnyLocalAddress) return false
    return networks.any { manager.getLinkProperties(it).hasLocalRoute(address) }
}

internal fun localRoute(context: Context, network: Network): Boolean =
    isPortalNetwork(context.getSystemService(ConnectivityManager::class.java).getNetworkCapabilities(network))

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

private class NoDelaySslSocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    private fun tune(socket: Socket): Socket = socket.apply {
        runCatching { tcpNoDelay = true }
    }

    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        tune(delegate.createSocket(s, host, port, autoClose))
    override fun createSocket(host: String, port: Int): Socket =
        tune(delegate.createSocket(host, port))
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        tune(delegate.createSocket(host, port, localHost, localPort))
    override fun createSocket(host: InetAddress, port: Int): Socket =
        tune(delegate.createSocket(host, port))
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        tune(delegate.createSocket(address, port, localAddress, localPort))
}

internal class PortalSession(
    private val peer: Peer,
    private val network: Network,
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
        val http = network.openConnection(url, Proxy.NO_PROXY) as HttpsURLConnection
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
        val http = network.openConnection(url, Proxy.NO_PROXY) as HttpsURLConnection
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
