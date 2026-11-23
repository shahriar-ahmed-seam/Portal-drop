package app.portaldrop

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

data class TransferProgress(
    val id: String,
    val incoming: Boolean,
    val name: String,
    val completedBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val etaSeconds: Long?,
)

class TransferService : Service() {
    enum class Status { PAUSED, CONNECTING, CONNECTED, OFFLINE, UNPAIRED }

    private val wakeups = LinkedBlockingQueue<Unit>(1)
    private val messageWakeups = LinkedBlockingQueue<Unit>(1)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val store by lazy { PortalStore.get(this) }
    private val discovery by lazy { PortalDiscovery(applicationContext) }
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private val observer = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = signal()

        override fun onLost(network: Network) {
            val link = currentLink
            if (link?.network == network) link.session.cancel()
            signal()
        }
    }

    @Volatile private var currentLink: Link? = null
    @Volatile private var discovered: Endpoint? = null
    @Volatile private var running = false
    @Volatile private var lastDisconnectedAt: Long = -CONNECT_NOTIFY_DEBOUNCE_MS
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            pause(this)
            return START_NOT_STICKY
        }
        if (store.peer() == null || (intent?.action != ACTION_START && !store.backgroundEnabled())) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!running) {
            createChannels()
            startForeground(SERVICE_ID, serviceNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            store.setBackgroundEnabled(true)
            running = true
            instance = this
            setStatus(Status.CONNECTING)
            val request = NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            connectivity.registerNetworkCallback(request, observer)
            observing = true
            worker.execute(::runTransfers)
            messageExecutor.execute(::runFastMessages)
        }
        signal()
        signalMessages()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        currentLink?.session?.cancel()
        discovery.stop()
        if (observing) connectivity.unregisterNetworkCallback(observer)
        signal()
        signalMessages()
        if (instance === this) {
            instance = null
            if (status != Status.UNPAIRED) {
                setStatus(if (store.backgroundEnabled()) Status.OFFLINE else Status.PAUSED)
            }
        }
        super.onDestroy()
    }

    private fun runTransfers() {
        var retryDelay = 1_000L
        var lastDiscovery = -DISCOVERY_INTERVAL_MS
        var preferredNetwork: Network? = null
        var previousNetworks: List<Network> = emptyList()
        var routeFailures = 0
        var idlePolls = 0
        try {
            while (running) {
                purgeExpiredAndNotify()
                val peer = store.peer() ?: break
                val networks = portalNetworks(this, peer.host)
                if (previousNetworks != networks) {
                    previousNetworks = networks
                    preferredNetwork = null
                    routeFailures = 0
                }
                if (networks.isEmpty()) {
                    dropLink()
                    markOffline()
                    await(15_000L)
                    continue
                }
                val network = preferredNetwork?.takeIf(networks::contains) ?: networks.first()
                val candidate = discovered?.takeIf { it.peer.fingerprint == peer.fingerprint && it.network == network }
                val endpoint = candidate?.let { peer.copy(host = it.host, port = it.port) } ?: peer
                val link = linkFor(endpoint, network)
                var delay: Long
                try {
                    val available = link.session.outbox()
                    if (!running) break
                    if (candidate != null) {
                        store.updateEndpoint(peer, candidate.host, candidate.port)
                        discovered = null
                        publishUpdate(this)
                    }
                    val freshPeer = store.peer() ?: peer
                    val wasConnected = status == Status.CONNECTED
                    setStatus(Status.CONNECTED)
                    if (!wasConnected) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastDisconnectedAt >= CONNECT_NOTIFY_DEBOUNCE_MS) {
                            notifyConnected(freshPeer)
                        }
                    }
                    ensureEventStream(link)
                    val needsChatBeforeSync = link.supportsChats == null ||
                        available.any { store.needsChat(freshPeer, it.chatId) }
                    if (needsChatBeforeSync) syncChats(freshPeer, link, available)
                    val work = sync(freshPeer, link, available)
                    if (!needsChatBeforeSync) syncChats(freshPeer, link, available)
                    if (work.active) idlePolls = 0 else idlePolls = (idlePolls + 1).coerceAtMost(20)
                    delay = when {
                        work.hasMore -> 50L
                        link.supportsEvents == true -> SSE_SAFETY_POLL_MS
                        idlePolls <= 3 -> ACTIVE_POLL_MS
                        else -> IDLE_POLL_MS
                    }
                    retryDelay = 1_000L
                    routeFailures = 0
                    preferredNetwork = network
                } catch (error: Exception) {
                    if (!running) break
                    dropLink()
                    if (candidate != null) discovered = null
                    if (error is PortalHttpException && (error.status == 401 || error.status == 403)) {
                        store.setBackgroundEnabled(false)
                        setStatus(Status.UNPAIRED)
                        break
                    }
                    Log.d(TAG, "The transfer connection will retry.", error)
                    markOffline()
                    val now = SystemClock.elapsedRealtime()
                    if (localRoute(this, network) && now - lastDiscovery >= DISCOVERY_INTERVAL_MS) {
                        lastDiscovery = now
                        discovery.find(peer, network) { host, port ->
                            if (running && (host != peer.host || port != peer.port)) {
                                discovered = Endpoint(peer, network, host, port)
                                signal()
                            }
                        }
                    }
                    routeFailures += 1
                    preferredNetwork = networks[(networks.indexOf(network) + 1) % networks.size]
                    if (routeFailures < networks.size) {
                        delay = 200L
                    } else {
                        routeFailures = 0
                        delay = retryDelay
                        retryDelay = (retryDelay * 2).coerceAtMost(DISCOVERY_INTERVAL_MS)
                    }
                }
                await(delay)
            }
        } finally {
            dropLink()
            if (running) stopSelf()
        }
    }

    private fun markOffline() {
        if (status == Status.CONNECTED) {
            lastDisconnectedAt = SystemClock.elapsedRealtime()
        }
        setStatus(Status.OFFLINE)
    }

    private fun purgeExpiredAndNotify() {
        val removed = runCatching { store.purgeExpired() }.getOrDefault(emptyList())
        if (removed.isNotEmpty()) {
            for ((id, isFile) in removed) {
                notifications.cancel(id, if (isFile) RECEIVED_ID else MESSAGE_ID)
            }
            publishUpdate(this)
        }
    }

    private fun ensureEventStream(link: Link) {
        if (link.supportsEvents == false || link.eventThreadActive) return
        link.eventThreadActive = true
        eventExecutor.execute {
            try {
                while (running && currentLink === link && link.supportsEvents != false) {
                    try {
                        link.session.streamEvents(
                            onReady = { link.supportsEvents = true },
                            onSync = {
                                signal()
                                signalMessages()
                            },
                        )
                        if (running && currentLink === link) {
                            Thread.sleep(300L)
                        }
                    } catch (error: PortalHttpException) {
                        if (error.status == 404 || error.status == 405) {
                            link.supportsEvents = false
                            break
                        }
                        if (error.status == 401 || error.status == 403) {
                            signal()
                            break
                        }
                        Thread.sleep(800L)
                    } catch (_: Exception) {
                        if (!running || currentLink !== link) break
                        Thread.sleep(800L)
                    }
                }
            } finally {
                link.eventThreadActive = false
            }
        }
    }

    private fun linkFor(peer: Peer, network: Network): Link {
        currentLink?.let {
            if (it.peer.fingerprint == peer.fingerprint &&
                it.peer.host == peer.host &&
                it.peer.port == peer.port &&
                it.network == network
            ) return it
        }
        dropLink()
        lateinit var link: Link
        val session = PortalSession(peer, network) { meta ->
            if (meta.protocolVersion > link.protocolVersion) {
                link.protocolVersion = meta.protocolVersion
            }
            if (store.updatePeerMeta(peer.fingerprint, meta.os, meta.name)) {
                publishUpdate(this)
            }
        }
        link = Link(peer, network, session)
        currentLink = link
        return link
    }

    private fun dropLink() {
        currentLink?.session?.cancel()
        currentLink = null
    }

    private fun syncChats(peer: Peer, link: Link, available: List<PortalItem>) {
        if (link.supportsChats == false) return
        val pending = store.dirtyChats(peer)
        val now = SystemClock.elapsedRealtime()
        if (link.supportsChats == null || now - link.lastChats >= CHATS_POLL_MS ||
            pending.isNotEmpty() || available.any { store.needsChat(peer, it.chatId) }
        ) {
            try {
                if (store.mergeChats(peer, link.session.chats())) publishUpdate(this)
                link.supportsChats = true
                link.lastChats = now
            } catch (error: PortalHttpException) {
                if (error.status != 404 && error.status != 405) throw error
                link.supportsChats = false
                return
            }
        }
        if (pending.isNotEmpty()) {
            for (chat in store.dirtyChats(peer)) {
                if (!running) return
                link.session.publishChat(chat)
                store.markChatSynced(peer, chat)
            }
        }
    }

    private data class SyncWork(val active: Boolean, val hasMore: Boolean)

    private fun sync(peer: Peer, link: Link, available: List<PortalItem>): SyncWork {
        val session = link.session
        val incoming = available.map { store.incoming(peer, it) }
        val outgoing = store.outgoing(peer)
        for (row in incoming.filter { it.delivered || it.deleted }) {
            if (!running) return SyncWork(false, false)
            if (!row.deleted && !row.notified) notifyIncoming(peer, row.item)
            session.acknowledge(row.item.id)
        }
        if (incoming.isNotEmpty()) publishUpdate(this)
        val sendable = outgoing.filter { row ->
            when {
                link.supportsChats == false &&
                    (row.item.chatId != DEFAULT_CHAT_ID || row.item.replyTo != null ||
                        row.item.textSize != null || row.item.ttlSec != null) -> {
                    fail(peer, row, "Update Portal Drop on your computer to deliver this item.")
                    false
                }
                row.item.ttlSec != null && link.protocolVersion < 3 -> {
                    fail(peer, row, "Update Portal Drop on your computer to send temporary messages.")
                    false
                }
                else -> true
            }
        }
        for (row in sendable.filter { it.item.binarySize == null }) {
            if (!running) return SyncWork(false, false)
            send(peer, row, session)
        }
        if (!running) return SyncWork(false, false)
        val incomingTransfers = incoming.filter { !it.delivered && !it.deleted }
        val received = incomingTransfers.any { running && receive(peer, it, session) }
        if (!running) return SyncWork(false, false)
        val outgoingTransfers = sendable.filter { it.item.binarySize != null }
        val sent = outgoingTransfers.any { running && send(peer, it, session) }
        return SyncWork(incoming.isNotEmpty() || sendable.isNotEmpty(),
            (received && incomingTransfers.size > 1) || (sent && outgoingTransfers.size > 1))
    }

    private fun runFastMessages() {
        while (running) {
            try {
                messageWakeups.poll(3_000L, TimeUnit.MILLISECONDS)
                if (!running) break
                val link = currentLink ?: continue
                if (status != Status.CONNECTED) continue
                val peer = store.peer() ?: continue
                if (peer.fingerprint != link.peer.fingerprint) continue
                syncFastMessages(peer, link)
            } catch (_: Exception) {
                // Main transfer loop handles reconnects/backoff; fast lane retries quietly.
            }
        }
    }

    private fun syncFastMessages(peer: Peer, link: Link) {
        val session = link.session
        // 1. Send outgoing inline text messages immediately even while bulk files upload/download.
        val outgoing = store.outgoingTexts(peer)
        for (row in outgoing) {
            if (!running || currentLink !== link) return
            if (link.supportsChats == false &&
                (row.item.chatId != DEFAULT_CHAT_ID || row.item.replyTo != null || row.item.ttlSec != null)
            ) {
                fail(peer, row, "Update Portal Drop on your computer to deliver this item.")
                continue
            }
            if (row.item.ttlSec != null && link.protocolVersion < 3) {
                fail(peer, row, "Update Portal Drop on your computer to send temporary messages.")
                continue
            }
            send(peer, row, session)
        }
        // 2. Receive & acknowledge incoming inline text messages without waiting for bulk files.
        val available = session.outbox()
        val fastIncoming = available.filter { it.binarySize == null }
        if (fastIncoming.isNotEmpty()) {
            if (link.supportsChats != false && fastIncoming.any { store.needsChat(peer, it.chatId) }) {
                runCatching {
                    if (store.mergeChats(peer, session.chats())) publishUpdate(this)
                }
            }
            var changed = false
            for (item in fastIncoming) {
                if (!running || currentLink !== link) return
                val key = "in:${item.id}"
                if (!inFlight.add(key)) continue
                try {
                    val row = store.incoming(peer, item)
                    if (!row.deleted && !row.notified) notifyIncoming(peer, row.item)
                    session.acknowledge(row.item.id)
                    changed = true
                } finally {
                    inFlight.remove(key)
                }
            }
            if (changed) publishUpdate(this)
        }
        // 3. Push any local chat renames/creates/deletes immediately.
        if (link.supportsChats == true && store.dirtyChats(peer).isNotEmpty()) {
            for (chat in store.dirtyChats(peer)) {
                if (!running || currentLink !== link) return
                session.publishChat(chat)
                store.markChatSynced(peer, chat)
            }
        }
    }

    private fun send(peer: Peer, row: ChatItem, session: PortalSession): Boolean {
        val item = row.item
        val key = "out:${item.id}"
        if (!inFlight.add(key)) return false
        try {
            if (store.isDoneOrDeleted(peer, row)) return false
            val sourceUri = if (item.kind == "file") row.sourceUri else null
            val path = when {
                sourceUri != null -> null
                item.kind == "file" -> store.stagedFile(item.id)
                item.textSize != null -> store.textFile(peer.fingerprint, item.id, incoming = false)
                else -> null
            }
            try {
                val offer = session.offer(item)
                if (!offer.received) {
                    if (sourceUri != null) {
                        showTransfer(row, offer.offset) { progress ->
                            session.uploadStream(item, offer.offset, {
                                openSourceUriAtOffset(sourceUri, offer.offset)
                            }, progress)
                        }
                    } else {
                        if (path == null) throw IOException("Your computer did not save the text message.")
                        if (!path.isFile || path.length() != item.binarySize) {
                            return fail(peer, row, "The queued data is missing or has changed.")
                        }
                        showTransfer(row, offer.offset) { progress ->
                            session.upload(item, offer.offset, path, progress)
                        }
                    }
                }
            } catch (error: PortalHttpException) {
                if (error.status in listOf(400, 413, 415, 422)) {
                    return fail(peer, row, "Computer could not accept this item (HTTP ${error.status}).")
                }
                throw error
            } catch (error: FileNotFoundException) {
                if (sourceUri != null) {
                    return fail(peer, row, "The original file is no longer accessible.")
                }
                throw error
            } catch (error: SecurityException) {
                if (sourceUri != null) {
                    return fail(peer, row, "Permission to read the original file was lost.")
                }
                throw error
            }
            store.complete(peer, item.id, incoming = false)
            if (item.kind == "file" && sourceUri == null) path?.delete()
            publishUpdate(this)
            return true
        } finally {
            inFlight.remove(key)
        }
    }

    private fun openSourceUriAtOffset(uri: Uri, offset: Long): InputStream {
        val pfd = try {
            contentResolver.openFileDescriptor(uri, "r")
        } catch (_: Exception) {
            null
        }
        if (pfd != null) {
            val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
            if (offset > 0) {
                try {
                    stream.channel.position(offset)
                } catch (_: IOException) {
                    skipFully(stream, offset)
                }
            }
            return stream
        }
        val input = contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException("Could not open source file.")
        if (offset > 0) skipFully(input, offset)
        return input
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        val discard = ByteArray(64 * 1024)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val read = input.read(discard, 0, minOf(remaining, discard.size.toLong()).toInt())
                if (read == -1) throw IOException("Source file ended unexpectedly.")
                remaining -= read
            }
        }
    }

    private fun receive(peer: Peer, row: ChatItem, session: PortalSession): Boolean {
        val key = "in:${row.item.id}"
        if (!inFlight.add(key)) return false
        try {
            return receiveLocked(peer, row, session)
        } finally {
            inFlight.remove(key)
        }
    }

    private fun receiveLocked(peer: Peer, row: ChatItem, session: PortalSession): Boolean {
        if (store.isDeleted(peer, row)) return false
        if (row.item.textSize != null) return receiveText(peer, row, session)
        val item = row.item
        val size = checkNotNull(item.size)
        val uri = try {
            prepareDownload(peer, row)
        } catch (error: IOException) {
            return fail(peer, row, error.message ?: "Could not prepare the download.")
        }
        val descriptor = try {
            contentResolver.openFileDescriptor(uri, "rw")
        } catch (_: FileNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        } ?: return fail(peer, row, "Could not open the download for writing.")
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
            val offset = output.channel.size()
            if (offset > size) output.channel.truncate(0)
            val start = if (offset > size) 0 else offset
            output.channel.position(start)
            if (start < size) {
                showTransfer(row, start) { progress ->
                    session.download(item, start) { input ->
                        val copied = input.copyTransfer(output, start, progress)
                        if (copied != size - start) throw IOException("The download ended before the file was complete.")
                    }
                }
                output.fd.sync()
            }
        }
        val digest = contentResolver.openInputStream(uri)?.use { it.sha256() }
        if (digest != item.sha256) {
            deleteTargetUri(uri)
            store.setDownloadUri(peer, item.id, null)
            return fail(peer, row, "The downloaded file failed verification. Portal Drop will retry.")
        }
        if (uri.authority == MediaStore.AUTHORITY) {
            val published = contentResolver.update(uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            if (published == 0) return fail(peer, row, "Could not finish saving the download.")
        }
        item.ttlSec?.let { store.refreshExpiry(peer, item.id, it) }
        store.complete(peer, item.id, incoming = true)
        if (!store.isDeleted(peer, row)) notifyIncoming(peer, item)
        publishUpdate(this)
        session.acknowledge(item.id)
        return true
    }

    private fun deleteTargetUri(uri: Uri) {
        if (uri.authority == MediaStore.AUTHORITY) {
            runCatching { contentResolver.delete(uri, null, null) }
            return
        }
        val deleted = runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }.getOrDefault(false)
        if (!deleted) runCatching { contentResolver.delete(uri, null, null) }
    }

    private fun receiveText(peer: Peer, row: ChatItem, session: PortalSession): Boolean {
        val item = row.item
        val size = checkNotNull(item.textSize)
        val blob = store.textFile(peer.fingerprint, item.id, incoming = true)
        if (blob.length() > size) RandomAccessFile(blob, "rw").use { it.setLength(0) }
        val offset = blob.length()
        if (offset < size) {
            showTransfer(row, offset) { progress ->
                blob.outputStreamAppend().use { output ->
                    session.download(item, offset) { input ->
                        val copied = input.copyTransfer(output, offset, progress)
                        if (copied != size - offset) throw IOException("The message is incomplete.")
                    }
                    output.fd.sync()
                }
            }
        }
        if (!store.verifyTextBlob(blob, checkNotNull(item.textSha256))) {
            blob.delete()
            return fail(peer, row, "The message failed verification. Portal Drop will retry.")
        }
        item.ttlSec?.let { store.refreshExpiry(peer, item.id, it) }
        store.complete(peer, item.id, incoming = true)
        if (!store.isDeleted(peer, row)) notifyIncoming(peer, item)
        publishUpdate(this)
        session.acknowledge(item.id)
        return true
    }

    private fun java.io.File.outputStreamAppend() = java.io.FileOutputStream(this, true)

    private fun prepareDownload(peer: Peer, row: ChatItem): Uri {
        row.downloadUri?.let { uri ->
            try {
                contentResolver.openFileDescriptor(uri, "r")?.use { return uri }
            } catch (_: Exception) {
                // The media provider or custom storage can expire/remove an abandoned partial file.
            }
        }
        val name = safeFilename(checkNotNull(row.item.name))
        val mime = resolveMimeType(name)
        val category = downloadCategory(name, mime)
        val customTree = store.saveTreeUri()
        if (customTree != null) {
            val uri = createSafDownload(customTree, category, name, mime)
            store.setDownloadUri(peer, row.item.id, uri)
            return uri
        }
        val downloads = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = contentResolver.insert(downloads, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Portal Drop/$category")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: throw IOException("Could not create a file in Downloads.")
        store.setDownloadUri(peer, row.item.id, uri)
        return uri
    }

    private fun createSafDownload(treeUri: Uri, category: String, name: String, mime: String): Uri {
        try {
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
            val targetFolderUri = findOrCreateSubdir(treeUri, rootDocId, rootDocUri, category) ?: rootDocUri
            return DocumentsContract.createDocument(contentResolver, targetFolderUri, mime, name)
                ?: throw IOException("Could not create file in the chosen save folder. Check your SD card or Save location.")
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("Could not write to the chosen save folder. Check your SD card or pick another Save location.", error)
        }
    }

    private fun findOrCreateSubdir(treeUri: Uri, parentDocId: String, parentDocUri: Uri, name: String): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        runCatching {
            contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0) ?: continue
                    val display = cursor.getString(1) ?: continue
                    val mimeType = cursor.getString(2) ?: ""
                    if (display == name && mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    }
                }
            }
        }
        return runCatching {
            DocumentsContract.createDocument(
                contentResolver,
                parentDocUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name,
            )
        }.getOrNull()
    }

    private fun downloadCategory(name: String, mime: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            mime.startsWith("audio/") || ext in setOf("aac", "flac", "m4a", "mp3", "ogg", "wav", "opus") -> "Music"
            mime.startsWith("video/") || ext in setOf("avi", "m4v", "mkv", "mov", "mp4", "webm") -> "Videos"
            mime.startsWith("image/") -> "Images"
            ext in setOf("7z", "zip", "rar", "tar", "gz", "bz2", "xz", "zst", "tgz", "tbz", "txz") -> "Compressed"
            ext in setOf("apk", "appimage", "deb", "rpm", "exe", "msi", "dmg", "pkg", "jar", "sh", "bin") -> "Programs"
            mime.startsWith("text/") || ext in setOf("pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx",
                "odt", "odp", "ods", "epub", "rtf") -> "Documents"
            else -> "Other"
        }
    }

    private fun showTransfer(row: ChatItem, start: Long, transfer: ((Long) -> Unit) -> Unit) {
        val size = checkNotNull(row.item.binarySize)
        val name = row.item.name ?: "Long message"
        val startedAt = SystemClock.elapsedRealtime()
        var lastUpdate = startedAt - 300L
        val report: (Long) -> Unit = report@{ completed ->
            val now = SystemClock.elapsedRealtime()
            if (now - lastUpdate < 300L && completed < size) return@report
            lastUpdate = now
            val elapsedMs = (now - startedAt).coerceAtLeast(1)
            val speed = if (elapsedMs < 300L) 0L else
                ((completed - start).toDouble() * 1_000 / elapsedMs).toLong()
            val eta = if (speed > 0) ceil((size - completed).coerceAtLeast(0) / speed.toDouble()).toLong() else null
            progress = TransferProgress(row.item.id, row.incoming, name, completed.coerceIn(0, size),
                size, speed, eta)
            publishUpdate(this)
        }
        report(start)
        val wifiLock = runCatching {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wm?.createWifiLock(mode, "PortalDrop:Transfer")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
        val wakeLock = runCatching {
            val pm = applicationContext.getSystemService(PowerManager::class.java)
            pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PortalDrop:Transfer")?.apply {
                setReferenceCounted(false)
                acquire(30 * 60 * 1000L)
            }
        }.getOrNull()
        try {
            transfer(report)
        } finally {
            runCatching { if (wifiLock?.isHeld == true) wifiLock.release() }
            runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
            progress = null
            publishUpdate(this)
        }
    }

    private fun fail(peer: Peer, row: ChatItem, reason: String): Boolean {
        if (reason != row.error) {
            store.setError(peer, row.item.id, row.incoming, reason)
            publishUpdate(this)
        }
        return false
    }

    private fun soundUri(rawName: String): Uri = Uri.parse("android.resource://$packageName/raw/$rawName")

    private fun createChannels() {
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        // Remove legacy v1/v2 channels so users upgrading immediately get the updated custom sounds.
        notifications.deleteNotificationChannel("received_files")
        notifications.deleteNotificationChannel("received_messages")
        notifications.deleteNotificationChannel("received_files_v2")
        notifications.deleteNotificationChannel("received_messages_v2")
        notifications.deleteNotificationChannel("device_connected_v2")

        notifications.createNotificationChannel(NotificationChannel(
            CONNECTION_CHANNEL, "Background connection", NotificationManager.IMPORTANCE_LOW,
        ))
        notifications.createNotificationChannel(NotificationChannel(
            FILE_CHANNEL, "Received files", NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setSound(soundUri("portal_file"), audioAttrs)
        })
        notifications.createNotificationChannel(NotificationChannel(
            FILE_SILENT_CHANNEL, "Received files (silent)", NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setSound(null, null)
            enableVibration(false)
        })
        notifications.createNotificationChannel(NotificationChannel(
            MESSAGE_CHANNEL, "Received messages", NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setSound(soundUri("portal_message"), audioAttrs)
        })
        notifications.createNotificationChannel(NotificationChannel(
            MESSAGE_SILENT_CHANNEL, "Received messages (silent)", NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setSound(null, null)
            enableVibration(false)
        })
        notifications.createNotificationChannel(NotificationChannel(
            CONNECTED_CHANNEL, "Device connected", NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            setSound(soundUri("portal_connected"), audioAttrs)
        })
        notifications.createNotificationChannel(NotificationChannel(
            CONNECTED_SILENT_CHANNEL, "Device connected (silent)", NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            setSound(null, null)
            enableVibration(false)
        })
    }

    private fun playRawSound(resId: Int) {
        try {
            val audio = getSystemService(AudioManager::class.java)
            if (audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
            val player = MediaPlayer()
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            resources.openRawResourceFd(resId).use { afd ->
                player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            player.setOnCompletionListener { it.release() }
            player.setOnErrorListener { mp, _, _ ->
                mp.release()
                true
            }
            player.prepare()
            player.start()
        } catch (error: Exception) {
            Log.d(TAG, "Could not play notification sound.", error)
        }
    }

    private fun serviceNotification(): Notification =
        Notification.Builder(this, CONNECTION_CHANNEL)
            .setSmallIcon(R.drawable.ic_portal_notification)
            .setContentTitle("Portal Drop")
            .setContentText("Background connection is on")
            .setContentIntent(openApp())
            .setOngoing(true)
            .addAction(Notification.Action.Builder(
                null, "Pause", PendingIntent.getService(this, 1,
                    Intent(this, TransferService::class.java).setAction(ACTION_PAUSE),
                    PendingIntent.FLAG_IMMUTABLE),
            ).build())
            .build()

    private fun notificationsAllowed(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notifyConnected(peer: Peer) {
        val prefs = store.notificationSettings()
        if (!prefs.connectedNotify) {
            if (prefs.connectedSound) playRawSound(R.raw.portal_connected)
            return
        }
        if (!notificationsAllowed()) return
        try {
            val label = peer.shortLabel()
            val channel = if (prefs.connectedSound) CONNECTED_CHANNEL else CONNECTED_SILENT_CHANNEL
            notifications.notify(
                "portal-connected",
                CONNECTED_ID,
                Notification.Builder(this, channel)
                    .setSmallIcon(R.drawable.ic_portal_notification)
                    .setContentTitle("Device connected")
                    .setContentText("Connected to $label")
                    .setContentIntent(openApp())
                    .setAutoCancel(true)
                    .build(),
            )
        } catch (error: RuntimeException) {
            Log.d(TAG, "Could not post connected alert.", error)
        }
    }

    private fun notifyIncoming(peer: Peer, item: PortalItem) {
        val prefs = store.notificationSettings()
        val isText = item.kind == "text"
        val showBanner = if (isText) prefs.messageNotify else prefs.fileNotify
        val playSound = if (isText) prefs.messageSound else prefs.fileSound
        try {
            if (!showBanner) {
                if (playSound) playRawSound(if (isText) R.raw.portal_message else R.raw.portal_file)
            } else if (notificationsAllowed()) {
                if (isText) {
                    showMessageNotification(peer, item, if (playSound) MESSAGE_CHANNEL else MESSAGE_SILENT_CHANNEL)
                } else {
                    val channel = if (playSound) FILE_CHANNEL else FILE_SILENT_CHANNEL
                    notifications.notify(item.id, RECEIVED_ID, Notification.Builder(this, channel)
                        .setSmallIcon(R.drawable.ic_portal_notification)
                        .setContentTitle("File received from ${peer.platformLabel()}")
                        .setContentText(item.name)
                        .setContentIntent(openApp(item.chatId))
                        .setAutoCancel(true)
                        .setOnlyAlertOnce(true)
                        .build())
                }
            }
        } catch (error: RuntimeException) {
            // Notifications are secondary to the durable chat and transfer receipt.
            Log.d(TAG, "Could not post the received item alert.", error)
        }
        store.markNotified(peer, item.id)
    }

    private fun showMessageNotification(peer: Peer, item: PortalItem, channelId: String) {
        val title = store.chat(peer, item.chatId)?.title ?: "Portal Drop"
        val preview = item.text?.take(1000) ?: "Long message received"
        val user = Person.Builder().setName("You").build()
        val sender = Person.Builder().setName(peer.platformLabel()).build()
        val reply = Intent(this, NotificationReplyReceiver::class.java)
            .setAction(NotificationReplyReceiver.ACTION_REPLY)
            .setData(Uri.parse("portaldrop-reply:/${item.id}"))
            .putExtra(EXTRA_CHAT_ID, item.chatId)
            .putExtra(NotificationReplyReceiver.EXTRA_FINGERPRINT, peer.fingerprint)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val replyIntent = PendingIntent.getBroadcast(this, 0, reply, flags)
        val remoteInput = RemoteInput.Builder(NotificationReplyReceiver.INPUT_REPLY).setLabel("Reply").build()
        val action = Notification.Action.Builder(null, "Reply", replyIntent)
            .addRemoteInput(remoteInput)
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            .setAllowGeneratedReplies(false)
            .build()
        notifications.notify(item.id, MESSAGE_ID, Notification.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_portal_notification)
            .setContentTitle(title)
            .setContentText(preview)
            .setStyle(Notification.MessagingStyle(user)
                .setConversationTitle(title)
                .setGroupConversation(false)
                .addMessage(preview, item.ts, sender))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(openApp(item.chatId))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .addAction(action)
            .build())
    }

    private fun openApp(chatId: String? = null): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        if (chatId != null) {
            intent.data = Uri.parse("portaldrop-chat:/$chatId")
            intent.putExtra(EXTRA_CHAT_ID, chatId)
        }
        return PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun signal() { wakeups.offer(Unit) }
    private fun signalMessages() { messageWakeups.offer(Unit) }
    private fun await(milliseconds: Long) { wakeups.poll(milliseconds, TimeUnit.MILLISECONDS) }

    private fun setStatus(newStatus: Status) {
        if (status != newStatus) {
            status = newStatus
            publishUpdate(this)
        }
    }

    private class Link(val peer: Peer, val network: Network, val session: PortalSession) {
        var supportsChats: Boolean? = null
        var supportsEvents: Boolean? = null
        @Volatile var protocolVersion: Int = 1
        @Volatile var eventThreadActive: Boolean = false
        var lastChats: Long = 0
    }
    private data class Endpoint(val peer: Peer, val network: Network, val host: String, val port: Int)

    companion object {
        const val ACTION_UPDATE = "app.portaldrop.TRANSFERS_UPDATED"
        const val EXTRA_CHAT_ID = "app.portaldrop.CHAT_ID"
        private const val ACTION_START = "app.portaldrop.START"
        private const val ACTION_PAUSE = "app.portaldrop.PAUSE"
        private const val CONNECTION_CHANNEL = "background_connection"
        private const val FILE_CHANNEL = "received_files_v3"
        private const val FILE_SILENT_CHANNEL = "received_files_silent_v3"
        private const val MESSAGE_CHANNEL = "received_messages_v3"
        private const val MESSAGE_SILENT_CHANNEL = "received_messages_silent_v3"
        private const val CONNECTED_CHANNEL = "device_connected_v3"
        private const val CONNECTED_SILENT_CHANNEL = "device_connected_silent_v3"
        private const val SERVICE_ID = 100
        private const val RECEIVED_ID = 101
        internal const val MESSAGE_ID = 102
        private const val CONNECTED_ID = 103
        private const val TAG = "PortalDrop"
        private const val ACTIVE_POLL_MS = 3_000L
        private const val IDLE_POLL_MS = 12_000L
        private const val SSE_SAFETY_POLL_MS = 25_000L
        private const val CHATS_POLL_MS = 30_000L
        private const val DISCOVERY_INTERVAL_MS = 30_000L
        private const val CONNECT_NOTIFY_DEBOUNCE_MS = 10_000L

        private val worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "Portal Drop transfers").apply { isDaemon = true }
        }
        private val messageExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "Portal Drop messages").apply { isDaemon = true }
        }
        private val eventExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "Portal Drop events").apply { isDaemon = true }
        }
        @Volatile private var instance: TransferService? = null
        @Volatile var status: Status = Status.PAUSED
            private set
        @Volatile var progress: TransferProgress? = null
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TransferService::class.java).setAction(ACTION_START))
        }

        fun pause(context: Context) {
            PortalStore.get(context).setBackgroundEnabled(false)
            instance?.let { service ->
                service.running = false
                service.currentLink?.session?.cancel()
                service.signal()
            }
            context.stopService(Intent(context, TransferService::class.java))
            status = Status.PAUSED
            publishUpdate(context)
        }

        fun wake() {
            instance?.signal()
            instance?.signalMessages()
        }

        fun wakeMessages() {
            instance?.signalMessages()
            instance?.signal()
        }

        internal fun publishUpdate(context: Context) {
            context.sendBroadcast(Intent(ACTION_UPDATE).setPackage(context.packageName))
        }
    }
}

class NotificationReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPLY) return
        val id = intent.data?.lastPathSegment ?: return
        val chatId = intent.getStringExtra(TransferService.EXTRA_CHAT_ID) ?: return
        val fingerprint = intent.getStringExtra(EXTRA_FINGERPRINT) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(INPUT_REPLY)?.toString() ?: return
        if (text.isBlank()) return
        val pending = goAsync()
        executor.execute {
            try {
                val store = PortalStore.get(context)
                if (store.peer()?.fingerprint == fingerprint && store.findItem(chatId, id)?.incoming == true) {
                    store.enqueueNotificationReply(text, chatId, id, fingerprint)
                    context.getSystemService(NotificationManager::class.java).cancel(id, TransferService.MESSAGE_ID)
                    TransferService.wake()
                    TransferService.publishUpdate(context)
                }
            } catch (error: Exception) {
                Log.d("PortalDrop", "Could not queue the notification reply.", error)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        internal const val ACTION_REPLY = "app.portaldrop.REPLY"
        internal const val INPUT_REPLY = "app.portaldrop.REPLY_TEXT"
        internal const val EXTRA_FINGERPRINT = "app.portaldrop.PEER_FINGERPRINT"
        private val executor = Executors.newSingleThreadExecutor()
    }
}

class TransferBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && PortalStore.get(context).backgroundEnabled()) {
            try {
                TransferService.start(context)
            } catch (error: IllegalStateException) {
                Log.d("PortalDrop", "Android deferred the background connection until the app is opened.", error)
            }
        }
    }
}
