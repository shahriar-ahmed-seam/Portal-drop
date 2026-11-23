package app.portaldrop

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.net.URI
import java.nio.charset.CodingErrorAction
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.json.JSONObject

private const val INLINE_TEXT_BYTES = 16_384
const val DEFAULT_CHAT_ID = "00000000-0000-0000-0000-000000000001"
const val TEMP_MESSAGE_TTL_SEC = 600L
private const val MIN_TTL_SEC = 10L
private const val MAX_TTL_SEC = 86_400L
private const val MAX_FILENAME_BYTES = 240
private const val CHAT_RANGE =
    "peer = ? AND chat_id = ? AND deleted = 0 AND (expires_at IS NULL OR expires_at > ?)"
private val hex256 = Regex("[0-9a-f]{64}")
private val itemId = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")

data class Peer(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val token: String,
    val os: String? = null,
    val name: String? = null,
) {
    fun platformLabel(): String = when (os?.lowercase(Locale.ROOT)) {
        "windows" -> "Windows PC"
        "linux" -> "Linux PC"
        "macos", "darwin" -> "Mac"
        "android" -> "Android"
        else -> "Computer"
    }

    fun shortLabel(): String = when {
        !name.isNullOrBlank() && !name.equals(platformLabel(), ignoreCase = true) ->
            "$name · ${platformLabel()}"
        else -> platformLabel()
    }
}

internal fun localAndroidDeviceName(): String {
    val raw = listOfNotNull(Build.MANUFACTURER?.trim(), Build.MODEL?.trim())
        .filter { it.isNotEmpty() }
        .let { parts ->
            if (parts.size == 2 && parts[1].startsWith(parts[0], ignoreCase = true)) parts[1]
            else parts.joinToString(" ")
        }
    return sanitizeHeaderAscii(raw) ?: "Android"
}

internal fun sanitizeHeaderAscii(raw: String?): String? {
    if (raw == null) return null
    val clean = raw.trim().filter { it.code in 0x20..0x7E }.take(64).trim()
    return clean.ifEmpty { null }
}

internal fun resolveMimeType(filename: String?, providerType: String? = null): String {
    val ext = filename?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
    val known = when (ext) {
        "apk" -> "application/vnd.android.package-archive"
        "pdf" -> "application/pdf"
        "epub" -> "application/epub+zip"
        "zip" -> "application/zip"
        "7z" -> "application/x-7z-compressed"
        "rar" -> "application/vnd.rar"
        "tar" -> "application/x-tar"
        "gz", "tgz" -> "application/gzip"
        "bz2", "tbz" -> "application/x-bzip2"
        "xz", "txz" -> "application/x-xz"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "odt" -> "application/vnd.oasis.opendocument.text"
        "ods" -> "application/vnd.oasis.opendocument.spreadsheet"
        "odp" -> "application/vnd.oasis.opendocument.presentation"
        "rtf" -> "application/rtf"
        "mkv" -> "video/x-matroska"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "wav" -> "audio/wav"
        "m4a", "aac" -> "audio/mp4"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "avif" -> "image/avif"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "csv" -> "text/csv"
        "html", "htm" -> "text/html"
        "md", "txt", "log", "yaml", "yml", "toml", "ini", "cfg", "conf",
        "sh", "py", "kt", "java", "rs", "js", "ts", "c", "cpp", "h", "hpp", "css", "sql" -> "text/plain"
        else -> null
    }
    if (known != null) return known
    if (!providerType.isNullOrBlank() && providerType != "application/octet-stream") return providerType
    if (ext.isNotEmpty()) {
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
    }
    return providerType ?: "application/octet-stream"
}

internal data class PairingInvite(val host: String, val port: Int, val fingerprint: String, val code: String) {
    companion object {
        fun parse(input: String): PairingInvite {
            require(input.length <= 1024) { "Pairing link is too long." }
            val uri = try {
                URI(input.trim())
            } catch (_: Exception) {
                throw IllegalArgumentException("Paste the pairing link shown on your computer.")
            }
            require(uri.scheme == "portaldrop" && uri.host != null && uri.port in 1..65535 &&
                uri.path == "/pair" && uri.rawUserInfo == null && uri.rawFragment == null
            ) { "Paste the pairing link shown on your computer." }
            val fields = uri.rawQuery.orEmpty().split('&')
            val query = fields.associate {
                val parts = it.split('=', limit = 2)
                parts.first() to parts.getOrElse(1) { "" }
            }
            val fingerprint = query["fp"].orEmpty()
            val code = query["code"].orEmpty()
            require(fields.size == 3 && query["v"] == "1" && hex256.matches(fingerprint) &&
                hex256.matches(code)
            ) { "Pairing link is invalid." }
            return PairingInvite(uri.host, uri.port, fingerprint, code)
        }
    }
}

data class PortalItem(
    val id: String,
    val kind: String,
    val ts: Long,
    val text: String? = null,
    val name: String? = null,
    val size: Long? = null,
    val sha256: String? = null,
    val chatId: String = DEFAULT_CHAT_ID,
    val replyTo: String? = null,
    val textSize: Long? = null,
    val textSha256: String? = null,
    val ttlSec: Long? = null,
) {
    internal val binarySize: Long? get() = size ?: textSize
}

data class ChatItem(
    val item: PortalItem,
    val incoming: Boolean,
    val delivered: Boolean,
    val downloadUri: Uri?,
    val error: String?,
    val seq: Long,
    internal val deleted: Boolean = false,
    internal val notified: Boolean = false,
    internal val peerFingerprint: String,
    val expiresAt: Long? = null,
)

data class ChatRoom(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
)
data class ChatPage(val items: List<ChatItem>, val olderThan: Long?)

class SharedTextFileProvider : FileProvider()

internal fun PortalItem.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("kind", kind)
    .put("ts", ts)
    .put("text", text)
    .put("name", name)
    .put("size", size)
    .put("sha256", sha256)
    .put("chat_id", chatId)
    .put("reply_to", replyTo)
    .put("text_size", textSize)
    .put("text_sha256", textSha256)
    .apply { if (ttlSec != null) put("ttl_sec", ttlSec) }

internal fun ChatRoom.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("title", title)
    .put("created_at", createdAt)
    .put("updated_at", updatedAt)
    .apply { if (deleted) put("deleted", true) }

internal fun chatFromJson(json: JSONObject): ChatRoom {
    val chat = ChatRoom(
        json.getString("id"),
        json.getString("title"),
        json.getLong("created_at"),
        json.getLong("updated_at"),
        json.optBoolean("deleted", false),
    )
    require(itemId.matches(chat.id) && validChatTitle(chat.title) &&
        chat.createdAt >= 0 && chat.updatedAt >= 0) { "Invalid chat details." }
    return chat
}

private fun validChatTitle(title: String): Boolean =
    title.isNotBlank() && title.toByteArray().size <= 240 && title.none(Char::isISOControl)

internal fun portalItemFromJson(value: JSONObject): PortalItem {
    val id = value.getString("id")
    val kind = value.getString("kind")
    val ts = value.getLong("ts")
    val chatId = if (value.isNull("chat_id")) DEFAULT_CHAT_ID else value.getString("chat_id")
    val replyTo = if (value.isNull("reply_to")) null else value.getString("reply_to")
    val ttlSec = if (value.has("ttl_sec") && !value.isNull("ttl_sec")) value.getLong("ttl_sec") else null
    require(itemId.matches(id) && ts >= 0 && itemId.matches(chatId) &&
        (replyTo == null || itemId.matches(replyTo)) &&
        (ttlSec == null || ttlSec in MIN_TTL_SEC..MAX_TTL_SEC)) { "Invalid item identity." }
    return when (kind) {
        "text" -> {
            if (!value.isNull("text")) {
                val content = value.getString("text")
                require(content.isNotBlank() && content.toByteArray().size <= INLINE_TEXT_BYTES) {
                    "Invalid text size."
                }
                PortalItem(id, kind, ts, text = content, chatId = chatId, replyTo = replyTo, ttlSec = ttlSec)
            } else {
                val size = value.getLong("text_size")
                val sha256 = value.getString("text_sha256")
                require(size > 0 && hex256.matches(sha256)) { "Invalid text attachment metadata." }
                PortalItem(id, kind, ts, chatId = chatId, replyTo = replyTo, textSize = size,
                    textSha256 = sha256, ttlSec = ttlSec)
            }
        }
        "file" -> {
            val name = value.getString("name")
            val size = value.getLong("size")
            val sha256 = value.getString("sha256")
            require(name.isNotBlank() && name.toByteArray().size <= MAX_FILENAME_BYTES &&
                size >= 0 && hex256.matches(sha256)
            ) { "Invalid file metadata." }
            PortalItem(id, kind, ts, name = name, size = size, sha256 = sha256, chatId = chatId,
                replyTo = replyTo, ttlSec = ttlSec)
        }
        else -> throw IllegalArgumentException("Unsupported item type.")
    }
}

internal fun safeFilename(name: String): String {
    val clean = name.map { char ->
        if (char == '/' || char == '\\' || char.isISOControl()) '_' else char
    }.joinToString("").trim { it.isWhitespace() || it == '.' }.ifBlank { "file" }
    var end = 0
    var bytes = 0
    while (end < clean.length) {
        val point = clean.codePointAt(end)
        val width = when {
            point <= 0x7f -> 1
            point <= 0x7ff -> 2
            point <= 0xffff -> 3
            else -> 4
        }
        if (bytes + width > MAX_FILENAME_BYTES) break
        bytes += width
        end += Character.charCount(point)
    }
    return clean.substring(0, end)
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

internal fun InputStream.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = read(buffer)
        if (read == -1) break
        digest.update(buffer, 0, read)
    }
    return digest.digest().hex()
}

class PortalStore private constructor(private val app: Context) :
    SQLiteOpenHelper(app, "portal-drop.db", null, 3) {

    private val settings = app.getSharedPreferences("portal_drop", Context.MODE_PRIVATE)
    private val staging = File(app.noBackupFilesDir, "outbox")

    companion object {
        @Volatile private var instance: PortalStore? = null

        fun get(context: Context): PortalStore = instance ?: synchronized(this) {
            instance ?: PortalStore(context.applicationContext).also { instance = it }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE items (
                seq INTEGER PRIMARY KEY,
                peer TEXT NOT NULL,
                id TEXT NOT NULL,
                incoming INTEGER NOT NULL,
                kind TEXT NOT NULL,
                ts INTEGER NOT NULL,
                text TEXT,
                name TEXT,
                size INTEGER,
                sha256 TEXT,
                done INTEGER NOT NULL,
                hidden INTEGER NOT NULL DEFAULT 0,
                media_uri TEXT,
                error TEXT,
                chat_id TEXT NOT NULL DEFAULT '$DEFAULT_CHAT_ID',
                reply_to TEXT,
                text_size INTEGER,
                text_sha256 TEXT,
                deleted INTEGER NOT NULL DEFAULT 0,
                notified INTEGER NOT NULL DEFAULT 0,
                ttl_sec INTEGER,
                expires_at INTEGER,
                UNIQUE (peer, incoming, id)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX items_by_peer ON items (peer, seq)")
        db.execSQL("CREATE INDEX items_expires ON items (peer, expires_at)")
        createChatTables(db, includeDeleted = true)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE items ADD COLUMN chat_id TEXT NOT NULL DEFAULT '$DEFAULT_CHAT_ID'")
            db.execSQL("ALTER TABLE items ADD COLUMN reply_to TEXT")
            db.execSQL("ALTER TABLE items ADD COLUMN text_size INTEGER")
            db.execSQL("ALTER TABLE items ADD COLUMN text_sha256 TEXT")
            db.execSQL("ALTER TABLE items ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE items ADD COLUMN notified INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE items SET notified = 1 WHERE incoming = 1 AND done = 1")
            createChatTables(db, includeDeleted = false)
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE items ADD COLUMN ttl_sec INTEGER")
            db.execSQL("ALTER TABLE items ADD COLUMN expires_at INTEGER")
            db.execSQL("CREATE INDEX IF NOT EXISTS items_expires ON items (peer, expires_at)")
            db.execSQL("ALTER TABLE chats ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
        }
    }

    private fun createChatTables(db: SQLiteDatabase, includeDeleted: Boolean) {
        db.execSQL("CREATE INDEX IF NOT EXISTS items_by_chat ON items (peer, chat_id, seq)")
        val deletedCol = if (includeDeleted) ", deleted INTEGER NOT NULL DEFAULT 0" else ""
        db.execSQL(
            """
            CREATE TABLE chats (
                peer TEXT NOT NULL,
                id TEXT NOT NULL,
                title TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                dirty INTEGER NOT NULL DEFAULT 0$deletedCol,
                PRIMARY KEY (peer, id)
            )
            """.trimIndent(),
        )
    }

    @Synchronized
    fun peer(): Peer? {
        val host = settings.getString("host", null) ?: return null
        val fingerprint = settings.getString("fingerprint", null) ?: return null
        val token = settings.getString("token", null) ?: return null
        val port = settings.getInt("port", 0)
        if (port !in 1..65535) return null
        val os = settings.getString("peer_os", null)
        val name = settings.getString("peer_name", null)
        return Peer(host, port, fingerprint, token, os, name)
    }

    fun backgroundEnabled(): Boolean = settings.getBoolean("background_enabled", false)

    internal fun setBackgroundEnabled(enabled: Boolean) {
        settings.edit().putBoolean("background_enabled", enabled).commit()
    }

    @Synchronized
    internal fun pairingToken(fingerprint: String): String {
        val existing = peer()
        if (existing?.fingerprint == fingerprint) return existing.token
        if (settings.getString("pending_fingerprint", null) == fingerprint) {
            settings.getString("pending_token", null)?.let { return it }
        }
        val token = ByteArray(32).also(SecureRandom()::nextBytes).hex()
        check(settings.edit().putString("pending_fingerprint", fingerprint)
            .putString("pending_token", token).commit()) { "Could not save pairing credentials." }
        return token
    }

    @Synchronized
    internal fun savePeer(peer: Peer) {
        check(settings.edit()
            .putString("host", peer.host).putInt("port", peer.port)
            .putString("fingerprint", peer.fingerprint).putString("token", peer.token)
            .apply {
                if (peer.os != null) putString("peer_os", peer.os) else remove("peer_os")
                if (peer.name != null) putString("peer_name", peer.name) else remove("peer_name")
            }
            .remove("pending_fingerprint").remove("pending_token").commit()) {
            "Could not save the paired device."
        }
    }

    @Synchronized
    internal fun updateEndpoint(original: Peer, host: String, port: Int) {
        val current = peer() ?: return
        if (current.fingerprint == original.fingerprint && (current.host != host || current.port != port)) {
            settings.edit().putString("host", host).putInt("port", port).commit()
        }
    }

    @Synchronized
    internal fun updatePeerMeta(fingerprint: String, os: String?, name: String?): Boolean {
        val current = peer() ?: return false
        if (current.fingerprint != fingerprint) return false
        val cleanOs = os?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        val cleanName = sanitizeHeaderAscii(name)
        val nextOs = cleanOs ?: current.os
        val nextName = cleanName ?: current.name
        if (nextOs == current.os && nextName == current.name) return false
        settings.edit().apply {
            if (nextOs != null) putString("peer_os", nextOs)
            if (nextName != null) putString("peer_name", nextName)
        }.apply()
        return true
    }

    @Synchronized
    fun forgetPeer() {
        settings.edit().remove("host").remove("port").remove("fingerprint").remove("token")
            .remove("peer_os").remove("peer_name")
            .remove("pending_fingerprint").remove("pending_token")
            .putBoolean("background_enabled", false).commit()
    }

    fun chats(): List<ChatRoom> {
        val current = peer() ?: return emptyList()
        val active = selectChats(current, "deleted = 0")
        if (active.isNotEmpty()) return active
        ensureInitialChat(current)
        return selectChats(current, "deleted = 0")
    }

    fun activeChatId(): String {
        val current = peer() ?: return DEFAULT_CHAT_ID
        val saved = settings.getString("active_chat_${current.fingerprint}", null)
        val active = chats()
        if (saved != null && active.any { it.id == saved }) return saved
        return active.firstOrNull()?.id ?: DEFAULT_CHAT_ID
    }

    fun setActiveChat(chatId: String) {
        val current = peer() ?: return
        val target = chat(current, chatId)
        require(target != null && !target.deleted) { "This chat is unavailable." }
        settings.edit().putString("active_chat_${current.fingerprint}", chatId).apply()
    }

    fun createChat(title: String? = null): ChatRoom {
        val current = peer() ?: error("Pair your computer first.")
        val now = System.currentTimeMillis()
        val actualTitle = title?.trim() ?: dateTitle(now)
        require(validChatTitle(actualTitle)) { "Enter a chat name of up to 240 bytes." }
        val chat = ChatRoom(newId(), actualTitle, now, now, deleted = false)
        require(writableDatabase.insertOrThrow("chats", null, chat.values(current, true)) != -1L)
        TransferService.wake()
        return chat
    }

    fun renameChat(chatId: String, title: String) {
        val current = peer() ?: error("Pair your computer first.")
        require(validChatTitle(title.trim())) { "Enter a chat name of up to 240 bytes." }
        val existing = chat(current, chatId)
        require(existing != null && !existing.deleted) { "This chat is unavailable." }
        val changed = writableDatabase.compileStatement(
            "UPDATE chats SET title = ?, updated_at = max(updated_at + 1, ?), dirty = 1 WHERE peer = ? AND id = ? AND deleted = 0",
        ).use { statement ->
            statement.bindString(1, title.trim())
            statement.bindLong(2, System.currentTimeMillis())
            statement.bindString(3, current.fingerprint)
            statement.bindString(4, chatId)
            statement.executeUpdateDelete()
        }
        check(changed == 1) { "This chat is unavailable." }
        TransferService.wake()
    }

    fun deleteChat(chatId: String): String {
        val current = peer() ?: error("Pair your computer first.")
        val active = chats()
        require(active.size > 1) { "Keep at least one chat." }
        require(active.any { it.id == chatId }) { "This chat is unavailable." }
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val privateFiles = mutableListOf<File>()
        db.beginTransaction()
        try {
            db.compileStatement(
                "UPDATE chats SET deleted = 1, updated_at = max(updated_at + 1, ?), dirty = 1 WHERE peer = ? AND id = ? AND deleted = 0",
            ).use { statement ->
                statement.bindLong(1, now)
                statement.bindString(2, current.fingerprint)
                statement.bindString(3, chatId)
                check(statement.executeUpdateDelete() == 1) { "This chat is unavailable." }
            }
            collectChatPrivateFiles(current, chatId, privateFiles)
            db.update(
                "items",
                ContentValues().apply {
                    put("deleted", 1)
                    put("done", 1)
                },
                "peer = ? AND chat_id = ?",
                arrayOf(current.fingerprint, chatId),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        privateFiles.forEach { it.delete() }
        val fallback = chats().first().id
        if (settings.getString("active_chat_${current.fingerprint}", null) == chatId) {
            settings.edit().putString("active_chat_${current.fingerprint}", fallback).apply()
        }
        TransferService.wake()
        return fallback
    }

    private fun collectChatPrivateFiles(peer: Peer, chatId: String, out: MutableList<File>) {
        val rows = list("peer = ? AND chat_id = ? AND deleted = 0", arrayOf(peer.fingerprint, chatId))
        for (row in rows) {
            if (row.item.textSize != null) out.add(textFile(peer.fingerprint, row.item.id, row.incoming))
            if (row.item.kind == "file" && !row.incoming) out.add(File(staging, row.item.id))
        }
    }

    fun chat(): List<ChatItem> = chatPage(activeChatId(), limit = 300).items

    fun chatPage(chatId: String, beforeSeq: Long? = null, limit: Int = 50): ChatPage {
        val current = peer() ?: return ChatPage(emptyList(), null)
        val args = chatRangeArgs(current, chatId)
        val pageSize = limit.coerceIn(1, 300)
        val page = if (beforeSeq == null) {
            list(CHAT_RANGE, args, "seq DESC", (pageSize + 1).toString())
        } else {
            list("$CHAT_RANGE AND seq < ?", args + beforeSeq.toString(),
                "seq DESC", (pageSize + 1).toString())
        }
        val items = page.take(pageSize).asReversed()
        return ChatPage(items, if (page.size > pageSize) items.first().seq else null)
    }

    fun chatFrom(chatId: String, earliestSequence: Long): List<ChatItem> {
        val current = peer() ?: return emptyList()
        return list(
            "$CHAT_RANGE AND seq >= ?",
            chatRangeArgs(current, chatId) + earliestSequence.toString(),
            "seq ASC",
        )
    }

    private fun chatRangeArgs(peer: Peer, chatId: String): Array<String> =
        arrayOf(peer.fingerprint, chatId, System.currentTimeMillis().toString())

    fun findItem(chatId: String, id: String): ChatItem? {
        val current = peer() ?: return null
        val now = System.currentTimeMillis().toString()
        return list(
            "peer = ? AND chat_id = ? AND id = ? AND deleted = 0 AND (expires_at IS NULL OR expires_at > ?)",
            arrayOf(current.fingerprint, chatId, id, now),
            "incoming DESC",
            "1",
        ).firstOrNull()
    }

    fun deleteItem(chatId: String, id: String, incoming: Boolean, deleteDownloadedFile: Boolean = false) {
        deleteItems(listOf(Triple(chatId, id, incoming)), deleteDownloadedFile)
    }

    fun deleteItems(targets: List<Triple<String, String, Boolean>>, deleteDownloadedFiles: Boolean = false): Int {
        val current = peer() ?: return 0
        if (targets.isEmpty()) return 0
        val db = writableDatabase
        val toRemovePrivate = mutableListOf<File>()
        val toRemoveMedia = mutableListOf<Uri>()
        var count = 0
        db.beginTransaction()
        try {
            for ((chatId, id, incoming) in targets.distinct()) {
                val predicate = "peer = ? AND chat_id = ? AND id = ? AND incoming = ? AND deleted = 0"
                val args = arrayOf(current.fingerprint, chatId, id, if (incoming) "1" else "0")
                val row = list(predicate, args, limit = "1").firstOrNull() ?: continue
                val values = ContentValues().apply {
                    put("deleted", 1)
                    if (!incoming) put("done", 1)
                    if (deleteDownloadedFiles && incoming && row.item.kind == "file") {
                        put("hidden", 1)
                        putNull("media_uri")
                    }
                }
                if (db.update("items", values, predicate, args) == 0) continue
                count += 1
                if (row.item.textSize != null && (!incoming || row.delivered)) {
                    toRemovePrivate.add(textFile(current.fingerprint, id, incoming))
                }
                if (row.item.kind == "file" && !incoming) {
                    toRemovePrivate.add(File(staging, id))
                }
                if (deleteDownloadedFiles && incoming && row.item.kind == "file" && row.downloadUri != null) {
                    toRemoveMedia.add(row.downloadUri)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        toRemovePrivate.forEach { it.delete() }
        toRemoveMedia.forEach { uri -> runCatching { app.contentResolver.delete(uri, null, null) } }
        return count
    }

    fun purgeExpired(): List<Pair<String, Boolean>> {
        val current = peer() ?: return emptyList()
        val now = System.currentTimeMillis()
        val expired = list(
            "peer = ? AND expires_at IS NOT NULL AND expires_at <= ?",
            arrayOf(current.fingerprint, now.toString()),
        )
        if (expired.isEmpty()) return emptyList()
        val db = writableDatabase
        val removed = mutableListOf<Pair<String, Boolean>>()
        db.beginTransaction()
        try {
            for (row in expired) {
                db.update(
                    "items",
                    ContentValues().apply {
                        put("deleted", 1)
                        put("done", 1)
                        put("hidden", 1)
                        putNull("text")
                        putNull("name")
                        putNull("media_uri")
                        putNull("expires_at")
                    },
                    "seq = ?",
                    arrayOf(row.seq.toString()),
                )
                removed.add(row.item.id to (row.item.kind == "file"))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        for (row in expired) {
            if (row.item.textSize != null) textFile(current.fingerprint, row.item.id, row.incoming).delete()
            if (row.item.kind == "file" && !row.incoming) File(staging, row.item.id).delete()
            if (row.item.kind == "file" && row.incoming && row.downloadUri != null) {
                runCatching { app.contentResolver.delete(row.downloadUri, null, null) }
            }
        }
        return removed
    }

    internal fun needsChat(peer: Peer, id: String): Boolean = chat(peer, id) == null

    internal fun dirtyChats(peer: Peer): List<ChatRoom> = selectChats(peer, "dirty = 1")

    internal fun markChatSynced(peer: Peer, chat: ChatRoom) {
        writableDatabase.update(
            "chats",
            ContentValues().apply { put("dirty", 0) },
            "peer = ? AND id = ? AND updated_at = ? AND title = ? AND deleted = ?",
            arrayOf(peer.fingerprint, chat.id, chat.updatedAt.toString(), chat.title, if (chat.deleted) "1" else "0"),
        )
    }

    internal fun mergeChats(peer: Peer, remote: List<ChatRoom>): Boolean {
        val db = writableDatabase
        var changed = false
        val filesToDelete = mutableListOf<File>()
        db.beginTransaction()
        try {
            for (chat in remote) {
                val local = chat(peer, chat.id)
                val remoteWins = when {
                    local == null -> true
                    chat.updatedAt > local.updatedAt -> true
                    chat.updatedAt == 0L && local.updatedAt == 0L && (chat.title != local.title || chat.deleted != local.deleted) -> true
                    chat.updatedAt == local.updatedAt -> when {
                        chat.deleted != local.deleted -> chat.deleted
                        else -> compareTitles(chat.title, local.title) > 0
                    }
                    else -> false
                }
                if (remoteWins) {
                    val winner = if (local == null) chat else chat.copy(createdAt = minOf(chat.createdAt, local.createdAt))
                    db.insertWithOnConflict("chats", null, winner.values(peer, false),
                        SQLiteDatabase.CONFLICT_REPLACE)
                    if (winner.deleted && local?.deleted != true) {
                        collectChatPrivateFiles(peer, winner.id, filesToDelete)
                        db.update(
                            "items",
                            ContentValues().apply {
                                put("deleted", 1)
                                put("done", 1)
                            },
                            "peer = ? AND chat_id = ?",
                            arrayOf(peer.fingerprint, winner.id),
                        )
                    }
                    changed = true
                } else if (local != null && chat.createdAt < local.createdAt) {
                    db.update("chats", ContentValues().apply { put("created_at", chat.createdAt) },
                        "peer = ? AND id = ?", arrayOf(peer.fingerprint, chat.id))
                    changed = true
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        filesToDelete.forEach { it.delete() }
        return changed
    }

    private fun ensureInitialChat(peer: Peer) {
        val any = selectChats(peer, "1 = 1")
        if (any.isEmpty()) {
            ensureChat(peer, DEFAULT_CHAT_ID)
            return
        }
        if (any.none { !it.deleted }) {
            val now = System.currentTimeMillis()
            val fresh = ChatRoom(newId(), dateTitle(now), now, now, deleted = false)
            writableDatabase.insertWithOnConflict("chats", null, fresh.values(peer, true),
                SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    private fun ensureChat(peer: Peer, id: String, timestamp: Long? = null) {
        require(itemId.matches(id)) { "Invalid chat ID." }
        if (chat(peer, id) != null) return
        val ts = timestamp ?: readableDatabase.rawQuery("SELECT MIN(ts) FROM items WHERE peer = ? AND chat_id = ?",
            arrayOf(peer.fingerprint, id)).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else System.currentTimeMillis()
        }
        val placeholder = ChatRoom(id, dateTitle(ts), ts, 0, deleted = false)
        writableDatabase.insertWithOnConflict("chats", null, placeholder.values(peer, false),
            SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun dateTitle(timestamp: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM)
        .format(Date(timestamp))

    internal fun chat(peer: Peer, id: String): ChatRoom? =
        selectChats(peer, "id = ?", arrayOf(id)).firstOrNull()

    private fun selectChats(peer: Peer, where: String, args: Array<String> = emptyArray()): List<ChatRoom> =
        readableDatabase.query("chats", arrayOf("id", "title", "created_at", "updated_at", "deleted"),
            "peer = ? AND $where", arrayOf(peer.fingerprint, *args), null, null, "created_at DESC, id").use { rows ->
            buildList {
                while (rows.moveToNext()) add(ChatRoom(
                    rows.getString(0),
                    rows.getString(1),
                    rows.getLong(2),
                    rows.getLong(3),
                    rows.getInt(4) != 0,
                ))
            }
        }

    private fun ChatRoom.values(peer: Peer, dirty: Boolean) = ContentValues().apply {
        put("peer", peer.fingerprint)
        put("id", id)
        put("title", title)
        put("created_at", createdAt)
        put("updated_at", updatedAt)
        put("dirty", dirty)
        put("deleted", if (deleted) 1 else 0)
    }

    private fun compareTitles(a: String, b: String): Int {
        val left = a.toByteArray()
        val right = b.toByteArray()
        for (index in 0 until minOf(left.size, right.size)) {
            val delta = (left[index].toInt() and 255) - (right[index].toInt() and 255)
            if (delta != 0) return delta
        }
        return left.size - right.size
    }

    fun receivedFiles(): List<ChatItem> = list(
        "peer = ? AND incoming = 1 AND kind = 'file' AND done = 1 AND hidden = 0 AND deleted = 0 AND (expires_at IS NULL OR expires_at > ?)",
        arrayOf(peer()?.fingerprint ?: "", System.currentTimeMillis().toString()),
        "seq DESC",
    )

    fun clearReceivedHistory() {
        val current = peer() ?: return
        writableDatabase.update("items", ContentValues().apply { put("hidden", 1) },
            "peer = ? AND incoming = 1 AND kind = 'file' AND done = 1", arrayOf(current.fingerprint))
    }

    fun enqueueText(
        text: String,
        chatId: String = activeChatId(),
        replyTo: String? = null,
        ttlSec: Long? = null,
    ) {
        saveOutgoingText(text, chatId, replyTo, newId(), null, ttlSec)
    }

    internal fun enqueueNotificationReply(text: String, chatId: String, replyTo: String, fingerprint: String) {
        val stableId = UUID.nameUUIDFromBytes("notification-reply:$fingerprint:$replyTo".toByteArray()).toString()
        saveOutgoingText(text, chatId, replyTo, stableId, fingerprint, null)
    }

    private fun saveOutgoingText(
        text: String,
        chatId: String,
        replyTo: String?,
        id: String,
        expectedFingerprint: String?,
        ttlSec: Long?,
    ) {
        val current = peer() ?: error("Pair your computer first.")
        if (expectedFingerprint != null && current.fingerprint != expectedFingerprint) return
        require(text.isNotBlank()) { "Enter a message." }
        require(ttlSec == null || ttlSec in MIN_TTL_SEC..MAX_TTL_SEC) { "Invalid temporary timer." }
        validateComposeTarget(current, chatId, replyTo)
        if (expectedFingerprint != null) {
            val existing = list("peer = ? AND incoming = 0 AND id = ?", arrayOf(current.fingerprint, id), limit = "1")
            if (existing.isNotEmpty()) return
        }
        val now = System.currentTimeMillis()
        val expiresAt = ttlSec?.let { now + it * 1000L }
        val inline = text.length <= INLINE_TEXT_BYTES && text.toByteArray().size <= INLINE_TEXT_BYTES
        if (inline) {
            insert(
                current,
                PortalItem(id, "text", now, text = text, chatId = chatId, replyTo = replyTo, ttlSec = ttlSec),
                incoming = false,
                done = false,
                ignoreDuplicate = expectedFingerprint != null,
                expiresAt = expiresAt,
            )
            return
        }
        val blob = textFile(current.fingerprint, id, incoming = false)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            OutputStreamWriter(DigestOutputStream(blob.outputStream(), digest), Charsets.UTF_8).use { it.write(text) }
            RandomAccessFile(blob, "rw").use { it.fd.sync() }
            check(peer()?.token == current.token) { "The paired device changed before this message could be queued." }
            insert(
                current,
                PortalItem(
                    id, "text", now, chatId = chatId, replyTo = replyTo,
                    textSize = blob.length(), textSha256 = digest.digest().hex(), ttlSec = ttlSec,
                ),
                incoming = false,
                done = false,
                ignoreDuplicate = expectedFingerprint != null,
                expiresAt = expiresAt,
            )
        } catch (error: Exception) {
            blob.delete()
            throw error
        }
    }

    fun enqueueFile(uri: Uri) = enqueueFiles(listOf(uri))

    fun attachmentName(uri: Uri): String {
        require(uri.scheme == "content") { "This attachment source is unsupported." }
        val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
        return safeFilename(name ?: "file")
    }

    fun enqueueFiles(
        uris: List<Uri>,
        chatId: String = activeChatId(),
        replyTo: String? = null,
        ttlSec: Long? = null,
    ) {
        val current = peer() ?: error("Pair your computer first.")
        require(ttlSec == null || ttlSec in MIN_TTL_SEC..MAX_TTL_SEC) { "Invalid temporary timer." }
        validateComposeTarget(current, chatId, replyTo)
        val idByUri = uris.distinct().associateWith { newId() }
        val expiresAt = ttlSec?.let { System.currentTimeMillis() + it * 1000L }
        try {
            val items = idByUri.map { (uri, id) -> stageFile(uri, id, chatId, replyTo, ttlSec) }
            val db = writableDatabase
            db.beginTransaction()
            try {
                check(peer()?.token == current.token) { "The paired device changed during import." }
                validateComposeTarget(current, chatId, replyTo)
                for (item in items) insert(current, item, incoming = false, done = false, expiresAt = expiresAt)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (error: Exception) {
            for (id in idByUri.values) File(staging, id).delete()
            throw error
        }
    }

    private fun validateComposeTarget(peer: Peer, chatId: String, replyTo: String?) {
        require(replyTo == null || itemId.matches(replyTo)) { "Invalid reply." }
        val existing = chat(peer, chatId)
        if (existing == null && chatId == DEFAULT_CHAT_ID) ensureChat(peer, chatId)
        val target = chat(peer, chatId)
        require(target != null && !target.deleted) { "This chat is unavailable." }
    }

    private fun stageFile(uri: Uri, id: String, chatId: String, replyTo: String?, ttlSec: Long?): PortalItem {
        val name = attachmentName(uri)
        val digest = MessageDigest.getInstance("SHA-256")
        val size = app.contentResolver.openInputStream(uri)?.use { input ->
            stagedFile(id).outputStream().use { output ->
                val copied = DigestInputStream(input, digest).copyTo(output, 64 * 1024)
                output.fd.sync()
                copied
            }
        } ?: throw IOException("Could not read the selected file.")
        return PortalItem(id, "file", System.currentTimeMillis(), name = name, size = size,
            sha256 = digest.digest().hex(), chatId = chatId, replyTo = replyTo, ttlSec = ttlSec)
    }

    internal fun stagedFile(id: String): File = storageFile(staging, id)

    internal fun textFile(fingerprint: String, id: String, incoming: Boolean): File {
        check(hex256.matches(fingerprint)) { "Invalid device fingerprint." }
        val direction = if (incoming) "incoming" else "outgoing"
        return storageFile(File(app.noBackupFilesDir, "text/$fingerprint/$direction"), id)
    }

    private fun storageFile(directory: File, id: String): File {
        check(itemId.matches(id)) { "Invalid file identifier." }
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Could not create Portal Drop’s file storage.")
        }
        return File(directory, id)
    }

    fun previewText(row: ChatItem): String? {
        row.item.text?.let { return it }
        val file = readableTextFile(row) ?: return null
        return file.bufferedReader(Charsets.UTF_8).use {
            val preview = CharArray(4096)
            val chars = it.read(preview)
            if (chars < 0) "" else String(preview, 0, chars)
        }
    }

    fun readText(row: ChatItem): String? = row.item.text ?: readableTextFile(row)?.readText(Charsets.UTF_8)

    fun sharedTextUri(row: ChatItem): Uri? {
        if (row.item.kind != "text") return null
        val fromFile = if (row.item.text == null) readableTextFile(row) ?: return null else null
        val direction = if (row.incoming) "received" else "sent"
        val directory = File(app.cacheDir, "shared-text/$direction")
        val destination = storageFile(directory, row.item.id)
        val output = File(directory, "${destination.name}.txt")
        if (!output.isFile) {
            val temp = File.createTempFile("export-", ".tmp", directory)
            try {
                if (fromFile != null) fromFile.copyTo(temp, overwrite = true)
                else temp.writeText(checkNotNull(row.item.text), Charsets.UTF_8)
                if (!temp.renameTo(output)) throw IOException("Could not prepare the message to share.")
            } finally {
                temp.delete()
            }
        }
        return FileProvider.getUriForFile(app, "${app.packageName}.shared-text", output)
    }

    private fun readableTextFile(row: ChatItem): File? = if (row.item.textSize != null &&
        (!row.incoming || row.delivered)) {
        textFile(row.peerFingerprint, row.item.id, row.incoming).takeIf(File::isFile)
    } else null

    internal fun verifyTextBlob(file: File, expectedSha256: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            BufferedReader(InputStreamReader(DigestInputStream(file.inputStream(), digest), decoder)).use {
                val chars = CharArray(8 * 1024)
                while (it.read(chars) != -1) { /* Validate UTF-8 without holding the text in memory. */ }
            }
            digest.digest().hex() == expectedSha256
        } catch (_: java.nio.charset.CharacterCodingException) {
            false
        }
    }

    internal fun outgoing(peer: Peer): List<ChatItem> = list(
        "peer = ? AND incoming = 0 AND done = 0 AND deleted = 0 AND (expires_at IS NULL OR expires_at > ?)",
        arrayOf(peer.fingerprint, System.currentTimeMillis().toString()),
        "CASE WHEN kind = 'text' AND text_size IS NULL THEN 0 ELSE 1 END, seq",
        "20",
    )

    internal fun incoming(peer: Peer, item: PortalItem): ChatItem {
        ensureChat(peer, item.chatId, item.ts)
        val expiresAt = item.ttlSec?.let { System.currentTimeMillis() + it * 1000L }
        insert(peer, item, incoming = true, done = item.binarySize == null, ignoreDuplicate = true, expiresAt = expiresAt)
        val stored = list("peer = ? AND incoming = 1 AND id = ?", arrayOf(peer.fingerprint, item.id)).single()
        val scrubbedTombstone = stored.deleted && stored.delivered && stored.item.text == null && stored.item.name == null
        if (!scrubbedTombstone) {
            require(stored.item == item) { "The sender reused an identifier with different contents." }
        }
        return stored
    }

    internal fun refreshExpiry(peer: Peer, id: String, ttlSec: Long) {
        val expiresAt = System.currentTimeMillis() + ttlSec * 1000L
        update(peer, id, incoming = true, ContentValues().apply { put("expires_at", expiresAt) })
    }

    internal fun markNotified(peer: Peer, id: String) {
        update(peer, id, incoming = true, ContentValues().apply { put("notified", 1) })
    }

    internal fun isDeleted(peer: Peer, item: ChatItem): Boolean =
        list("peer = ? AND incoming = ? AND id = ? AND deleted = 1",
            arrayOf(peer.fingerprint, if (item.incoming) "1" else "0", item.item.id), limit = "1").isNotEmpty()

    internal fun setDownloadUri(peer: Peer, id: String, uri: Uri?) {
        val values = ContentValues().apply { put("media_uri", uri?.toString()) }
        update(peer, id, incoming = true, values)
    }

    internal fun complete(peer: Peer, id: String, incoming: Boolean) {
        val values = ContentValues().apply {
            put("done", 1)
            putNull("error")
        }
        update(peer, id, incoming, values)
    }

    internal fun setError(peer: Peer, id: String, incoming: Boolean, error: String?) {
        update(peer, id, incoming, ContentValues().apply { put("error", error) })
    }

    private fun update(peer: Peer, id: String, incoming: Boolean, values: ContentValues) {
        writableDatabase.update("items", values, "peer = ? AND incoming = ? AND id = ?",
            arrayOf(peer.fingerprint, if (incoming) "1" else "0", id))
    }

    private fun insert(
        peer: Peer,
        item: PortalItem,
        incoming: Boolean,
        done: Boolean,
        ignoreDuplicate: Boolean = false,
        expiresAt: Long? = null,
    ) {
        val values = ContentValues().apply {
            put("peer", peer.fingerprint)
            put("id", item.id)
            put("incoming", incoming)
            put("kind", item.kind)
            put("ts", item.ts)
            put("text", item.text)
            put("name", item.name)
            put("size", item.size)
            put("sha256", item.sha256)
            put("chat_id", item.chatId)
            put("reply_to", item.replyTo)
            put("text_size", item.textSize)
            put("text_sha256", item.textSha256)
            put("ttl_sec", item.ttlSec)
            put("expires_at", expiresAt)
            put("done", done)
        }
        if (ignoreDuplicate) {
            writableDatabase.insertWithOnConflict("items", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        } else {
            writableDatabase.insertOrThrow("items", null, values)
        }
    }

    private fun list(where: String, args: Array<String>, order: String? = null,
                     limit: String? = null): List<ChatItem> =
        readableDatabase.query("items", null, where, args, null, null, order, limit).use { rows ->
            buildList { while (rows.moveToNext()) add(rows.chatItem()) }
        }

    private fun Cursor.chatItem(): ChatItem {
        fun string(column: String) = getString(getColumnIndexOrThrow(column))
        fun long(column: String) = getLong(getColumnIndexOrThrow(column))
        fun optionalLong(column: String): Long? {
            val index = getColumnIndexOrThrow(column)
            return if (isNull(index)) null else getLong(index)
        }
        val item = PortalItem(
            string("id"), string("kind"), long("ts"), string("text"), string("name"),
            optionalLong("size"), string("sha256"), string("chat_id"), string("reply_to"),
            optionalLong("text_size"), string("text_sha256"), optionalLong("ttl_sec"),
        )
        return ChatItem(
            item,
            long("incoming") == 1L,
            long("done") == 1L,
            string("media_uri")?.let(Uri::parse),
            string("error"),
            long("seq"),
            long("deleted") == 1L,
            long("notified") == 1L,
            string("peer"),
            optionalLong("expires_at"),
        )
    }

    private fun newId(): String = UUID.randomUUID().toString()
}
