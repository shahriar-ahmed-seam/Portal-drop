package app.portaldrop

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.Reader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

internal class SharedMediaView(
    context: Context,
    private val ui: PortalViews,
    private val store: PortalStore,
    private val chatId: String,
    private val previews: MediaPreviews,
    private val worker: ExecutorService,
    private val openFile: (ChatItem) -> Unit,
    private val openLink: (Uri) -> Unit,
) : LinearLayout(context) {
    private enum class Tab(val title: String, val empty: String) {
        PHOTOS("Photos", "No photos in this chat"),
        VIDEOS("Videos", "No videos in this chat"),
        FILES("Files", "No files in this chat"),
        LINKS("Links", "No links in this chat"),
    }

    private data class Link(val address: String, val uri: Uri, val timestamp: Long)
    private data class Files(val all: List<ChatItem>, val photos: List<ChatItem>, val videos: List<ChatItem>)
    private class Load(val isLinks: Boolean) {
        val signal = CancellationSignal()
        var future: Future<*>? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private val labels = mutableMapOf<Tab, View>()
    private val content = FrameLayout(context)
    private var tab = Tab.PHOTOS
    private var listing: MediaGrid? = null
    private var rows: SharedList? = null
    private var empty: TextView? = null
    private var files: Files? = null
    private var links: List<Link>? = null
    private val messageLinks = object : LruCache<Long, Set<String>>(2 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Set<String>): Int = 96 + value.sumOf { 64 + it.length * 2 }
    }
    private var activeLoad: Load? = null
    private var filesDirty = true
    private var linksDirty = true
    private var loadScheduled = false
    private val loadSelected = Runnable {
        loadScheduled = false
        load()
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(ui.canvasColor)
        val tabs = ui.row().apply {
            setBackgroundColor(ui.surface)
            setPadding(ui.dp(7), 0, ui.dp(7), 0)
        }
        for (option in Tab.entries) {
            val button = ui.action(option.title) {
                if (tab != option) {
                    val changedQuery = (tab == Tab.LINKS) != (option == Tab.LINKS)
                    tab = option
                    if (changedQuery) cancelLoad()
                    showTab()
                    scheduleLoad()
                }
            }
            labels[option] = button
            tabs.addView(button, LayoutParams(0, ui.dp(48), 1f))
        }
        addView(tabs, LayoutParams(MATCH, WRAP))
        addView(ui.rule())
        addView(content, LayoutParams(MATCH, 0, 1f))
        showTab()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refresh()
    }

    override fun onDetachedFromWindow() {
        cancelLoad()
        super.onDetachedFromWindow()
    }

    fun refresh() {
        filesDirty = true
        linksDirty = true
        scheduleLoad()
    }

    private fun cancelLoad() {
        handler.removeCallbacks(loadSelected)
        loadScheduled = false
        activeLoad?.let { request ->
            if (request.isLinks) linksDirty = true else filesDirty = true
            request.signal.cancel()
            request.future?.cancel(true)
        }
        activeLoad = null
    }

    private fun scheduleLoad() {
        val needsLoad = if (tab == Tab.LINKS) linksDirty else filesDirty
        if (!needsLoad || !isAttachedToWindow || loadScheduled || activeLoad != null) return
        loadScheduled = true
        handler.postDelayed(loadSelected, LOAD_DELAY_MS)
    }

    private fun load() {
        if (!isAttachedToWindow || worker.isShutdown) return
        val request = Load(tab == Tab.LINKS)
        activeLoad = request
        if (request.isLinks) linksDirty = false else filesDirty = false
        val previousFiles = files
        val previousLinks = links
        request.future = worker.submit {
            val result = runCatching {
                if (request.isLinks) null to readLinks(request.signal).takeUnless { it == previousLinks }
                else readFiles(request.signal, previousFiles) to null
            }
            if (request.signal.isCanceled) return@submit
            handler.post {
                if (activeLoad !== request || !isAttachedToWindow) return@post
                activeLoad = null
                result.onSuccess { (newFiles, newLinks) ->
                    if (newFiles != null) files = newFiles
                    if (newLinks != null) links = newLinks
                    updateListing()
                }.onFailure {
                    val hasList = if (request.isLinks) links != null else files != null
                    if (!hasList) empty?.apply {
                        text = "Could not load ${tab.title.lowercase()}"
                        visibility = VISIBLE
                    }
                }
                scheduleLoad()
            }
        }
    }

    private fun readFiles(signal: CancellationSignal, previous: Files?): Files? {
        val all = store.sharedFiles(chatId, signal)
        if (all == previous?.all) return null
        val photos = mutableListOf<ChatItem>()
        val videos = mutableListOf<ChatItem>()
        for (file in all) {
            signal.throwIfCanceled()
            when (file.visualMedia()) {
                VisualMedia.PHOTO -> photos.add(file)
                VisualMedia.VIDEO -> videos.add(file)
                null -> Unit
            }
        }
        return Files(all, photos, videos)
    }

    private fun readLinks(signal: CancellationSignal): List<Link> {
        val unique = linkedMapOf<String, Link>()
        var beforeSeq = Long.MAX_VALUE
        while (true) {
            signal.throwIfCanceled()
            val messages = store.sharedLinkMessages(chatId, beforeSeq, signal)
            if (messages.isEmpty()) break
            for (message in messages) {
                signal.throwIfCanceled()
                try {
                    val addresses = messageLinks.get(message.seq) ?: store.openTextReader(message)?.use { reader ->
                        webLinks(reader).also {
                            signal.throwIfCanceled()
                            messageLinks.put(message.seq, it)
                        }
                    }.orEmpty()
                    for (address in addresses) {
                        if (!unique.containsKey(address)) {
                            unique[address] = Link(address, Uri.parse(address), message.item.ts)
                        }
                    }
                } catch (_: java.io.IOException) {
                    // The user can delete or expire a long message while shared links are loading.
                }
            }
            beforeSeq = messages.last().seq
        }
        signal.throwIfCanceled()
        return unique.values.toList()
    }

    private fun showTab() {
        labels.forEach { (option, view) ->
            val active = tab == option
            view.isSelected = active
            (view as TextView).setTextColor(if (active) ui.accentText else ui.muted)
            view.background = ui.tapShape(if (active) ui.mint else android.graphics.Color.TRANSPARENT, 13)
        }
        content.removeAllViews()
        listing = null
        rows = null
        if (tab == Tab.PHOTOS || tab == Tab.VIDEOS) {
            val grid = GridView(context).apply {
                numColumns = GridView.AUTO_FIT
                columnWidth = ui.dp(102)
                horizontalSpacing = ui.dp(5)
                verticalSpacing = ui.dp(5)
                stretchMode = GridView.STRETCH_COLUMN_WIDTH
                clipToPadding = false
                setPadding(ui.dp(7), ui.dp(7), ui.dp(7), ui.dp(14))
                setSelector(android.R.color.transparent)
                setRecyclerListener { (it as? MediaThumbnail)?.recycle() }
                listing = MediaGrid(if (tab == Tab.VIDEOS) VisualMedia.VIDEO else VisualMedia.PHOTO)
                adapter = listing
                setOnItemClickListener { _, _, position, _ -> listing?.getItem(position)?.let(openFile) }
            }
            content.addView(grid, FrameLayout.LayoutParams(MATCH, MATCH))
        } else {
            val list = ListView(context).apply {
                divider = null
                clipToPadding = false
                setPadding(ui.dp(7), ui.dp(5), ui.dp(7), ui.dp(14))
                setSelector(android.R.color.transparent)
                rows = SharedList()
                adapter = rows
                setOnItemClickListener { _, _, position, _ -> rows?.open(position) }
            }
            content.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        empty = ui.label(tab.empty, 15, ui.muted).apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12))
            visibility = GONE
        }
        content.addView(empty, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        updateListing()
    }

    private fun updateListing() {
        val count = when (tab) {
            Tab.PHOTOS, Tab.VIDEOS -> {
                val matching = if (tab == Tab.VIDEOS) files?.videos.orEmpty() else files?.photos.orEmpty()
                listing?.replace(matching)
                matching.size
            }
            Tab.FILES -> {
                val currentFiles = files?.all.orEmpty()
                rows?.replace(currentFiles, emptyList())
                currentFiles.size
            }
            Tab.LINKS -> {
                rows?.replace(emptyList(), links.orEmpty())
                links.orEmpty().size
            }
        }
        val loaded = if (tab == Tab.LINKS) links != null else files != null
        empty?.apply {
            text = tab.empty
            visibility = if (loaded && count == 0) VISIBLE else GONE
        }
    }

    private inner class MediaGrid(private val media: VisualMedia) : BaseAdapter() {
        private var items = emptyList<ChatItem>()

        fun replace(newItems: List<ChatItem>) {
            if (items === newItems) return
            items = newItems
            notifyDataSetChanged()
        }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].seq
        override fun hasStableIds() = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            (convertView as? MediaThumbnail ?: MediaThumbnail(context, ui, previews, square = true)).apply {
                bind(getItem(position), media)
            }
    }

    private inner class SharedList : BaseAdapter() {
        private var fileItems = emptyList<ChatItem>()
        private var linkItems = emptyList<Link>()

        fun replace(files: List<ChatItem>, links: List<Link>) {
            if (fileItems === files && linkItems === links) return
            fileItems = files
            linkItems = links
            notifyDataSetChanged()
        }

        fun open(position: Int) {
            fileItems.getOrNull(position)?.let(openFile)
            linkItems.getOrNull(position)?.let { openLink(it.uri) }
        }

        override fun getCount() = fileItems.size + linkItems.size
        override fun getItem(position: Int): Any = fileItems.getOrNull(position) ?: linkItems[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val attachment = fileItems.getOrNull(position)
            val link = linkItems.getOrNull(position)
            val name = attachment?.item?.relativePath ?: attachment?.item?.name ?: link?.address ?: "Attachment"
            val timestamp = attachment?.item?.ts ?: link?.timestamp ?: 0L
            val date = DateUtils.formatDateTime(context, timestamp, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
            val detail = if (attachment != null) {
                val status = when (attachment.transferState) {
                    TransferState.DELIVERED -> if (attachment.incoming) "Received" else "Sent"
                    TransferState.QUEUED -> "Queued"
                    TransferState.PAUSED -> "Paused"
                    TransferState.CANCELED -> "Canceled"
                    TransferState.FAILED -> "Failed"
                }
                listOfNotNull(
                    attachment.item.size?.let { Formatter.formatShortFileSize(context, it) },
                    status,
                    date,
                ).joinToString(" · ")
            } else listOfNotNull(link?.uri?.host, date).joinToString(" · ")
            val icon = when {
                link != null -> R.drawable.ic_media_link
                attachment?.visualMedia() == VisualMedia.VIDEO -> R.drawable.ic_chat_movie
                attachment?.visualMedia() == VisualMedia.PHOTO -> R.drawable.ic_media_photo
                else -> R.drawable.ic_chat_file
            }
            return ui.row().apply {
                background = ui.tapShape(ui.surface, 12)
                minimumHeight = ui.dp(63)
                setPadding(ui.dp(10), ui.dp(7), ui.dp(10), ui.dp(7))
                addView(ui.symbol(icon, ui.onAccent).apply {
                    setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
                    background = ui.shape(ui.accent, 22)
                }, LayoutParams(ui.dp(42), ui.dp(42)).apply { marginEnd = ui.dp(12) })
                addView(ui.column().apply {
                    addView(ui.label(name, 15, ui.ink, bold = true).apply {
                        setSingleLine(true)
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    addWithGap(ui.label(detail, 12, ui.muted), 2)
                }, LayoutParams(0, WRAP, 1f))
            }
        }
    }

    private companion object {
        const val LOAD_DELAY_MS = 180L
    }
}

internal fun webLinks(reader: Reader): Set<String> {
    val found = linkedSetOf<String>()
    val token = StringBuilder()
    val buffer = CharArray(4096)
    var overflow = false

    fun finishToken() {
        val hasLinkPrefix = token.contains("http", ignoreCase = true) || token.contains("www.", ignoreCase = true)
        val matches = if (!overflow && hasLinkPrefix) WEB_LINK.findAll(token) else emptySequence()
        for (match in matches) {
            var address = match.value.trimEnd('.', ',', ':', ';', '!', '?')
            for ((open, close) in BRACKETS) {
                var excess = address.count { it == close } - address.count { it == open }
                var end = address.length
                while (excess > 0 && end > 0 && address[end - 1] == close) {
                    end -= 1
                    excess -= 1
                }
                address = address.substring(0, end)
            }
            if (address.startsWith("www.", ignoreCase = true)) address = "https://$address"
            val uri = runCatching { Uri.parse(address).normalizeScheme() }.getOrNull() ?: continue
            val host = uri.host
            if (uri.scheme in WEB_SCHEMES && host != null && host.length <= 253 && host.any(Char::isLetterOrDigit)) {
                found.add(uri.toString())
            }
        }
        token.clear()
        overflow = false
    }

    while (!Thread.currentThread().isInterrupted) {
        val count = reader.read(buffer)
        if (count == -1) break
        for (offset in 0 until count) {
            val char = buffer[offset]
            if (char.isWhitespace() || char in "<>\"'`") {
                if (token.isNotEmpty()) finishToken()
            } else if (token.length == MAX_LINK_TOKEN) {
                overflow = true
            } else {
                token.append(char)
            }
        }
    }
    if (token.isNotEmpty()) finishToken()
    return found
}

private val WEB_LINK = Regex("""(?i)(?:https?://|www\.)[^\s<>"'`]+""")
private val BRACKETS = listOf('(' to ')', '[' to ']', '{' to '}')
private val WEB_SCHEMES = setOf("http", "https")
private const val MAX_LINK_TOKEN = 4096
