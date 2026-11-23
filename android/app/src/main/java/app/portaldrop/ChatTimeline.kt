package app.portaldrop

import android.content.Context
import android.content.res.ColorStateList
import android.net.Uri
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.ProgressBar
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

private sealed interface TimelineEntry {
    val stableId: Long

    data object Earlier : TimelineEntry { override val stableId = 0L }
    data class Date(val timestamp: Long, val day: LocalDate, val firstSequence: Long) : TimelineEntry {
        override val stableId = -firstSequence
    }
    data class Message(val row: ChatItem) : TimelineEntry { override val stableId = row.seq }
}

internal fun canRead(row: ChatItem) = !row.incoming || row.delivered

internal fun author(row: ChatItem, peerLabel: String = "Computer") =
    if (row.incoming) peerLabel else "You"

internal fun previewOf(row: ChatItem): String {
    if (row.item.kind == "file") return row.item.name ?: "Attachment"
    return row.item.text?.take(160)
        ?: if (canRead(row)) "Large text message" else "Receiving text"
}

internal fun formatRemainingLabel(expiresAt: Long): String {
    val remainingSec = ((expiresAt - System.currentTimeMillis()) / 1000L).toInt()
    return when {
        remainingSec <= 0 -> "Expiring"
        remainingSec < 60 -> "<1m left"
        else -> "${(remainingSec + 59) / 60}m left"
    }
}

internal class RoomAdapter(
    private val context: Context,
    private val ui: PortalViews,
    private val activateChat: (String) -> Unit,
    private val showChatMenu: (View, ChatRoom) -> Unit,
) : BaseAdapter() {
    private var items = emptyList<ChatRoom>()
    private var activeId: String? = null

    fun replace(chats: List<ChatRoom>, active: String) {
        if (items == chats && activeId == active) return
        items = chats
        activeId = active
        notifyDataSetChanged()
    }

    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val room = getItem(position)
        val active = room.id == activeId
        val foreground = if (active) -1 else ui.ink
        val metaColor = if (active) 0xFFE0F4E7.toInt() else ui.muted
        val body = ui.row().apply {
            setPadding(ui.dp(10), ui.dp(7), ui.dp(6), ui.dp(7))
            minimumHeight = ui.dp(66)
            isSelected = active
            val fill = if (active) context.getColor(R.color.portal_room_active) else ui.canvasColor
            background = ui.tapShape(fill, 12)
            setOnClickListener { activateChat(room.id) }
            setOnLongClickListener {
                showChatMenu(this, room)
                true
            }
        }
        body.addView(ui.avatar(room.id, room.title, 48),
            LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply { marginEnd = ui.dp(12) })
        val description = ui.column()
        description.addView(ui.label(room.title, 16, foreground, bold = true).apply {
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        })
        val stamp = if (room.updatedAt > 0) room.updatedAt else room.createdAt
        val last = DateUtils.getRelativeTimeSpanString(stamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        description.addView(ui.label(last.toString(), 12, metaColor))
        body.addView(description, LinearLayout.LayoutParams(0, WRAP, 1f))
        body.addView(ui.iconAction(R.drawable.ic_chat_more, "Options for ${room.title}", color = metaColor) {
            showChatMenu(it, room)
        }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        return FrameLayout(context).apply {
            setPadding(0, ui.dp(3), 0, ui.dp(3))
            addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        }
    }
}

internal class ChatTimelineAdapter(
    private val context: Context,
    private val ui: PortalViews,
    private val store: PortalStore,
    private val received: Boolean,
    private val loadOlder: () -> Unit,
    private val openFile: (ChatItem) -> Unit,
    private val showFullMessage: (ChatItem) -> Unit,
    private val showMessageMenu: (View, ChatItem) -> Unit,
    private val toggleSelect: (ChatItem) -> Unit,
) : BaseAdapter() {
    private var items = emptyList<ChatItem>()
    private var entries = emptyList<TimelineEntry>()
    private var progress: TransferProgress? = null
    private var earlier = false
    private var selecting = false
    private var selectedSeqs = emptySet<Long>()
    private var lastMinuteBucket = 0L
    private val rendered = object : LruCache<Long, CharSequence>(80) {}

    fun visibleItems(): List<ChatItem> = items

    fun replace(
        newItems: List<ChatItem>,
        hasEarlier: Boolean,
        transfer: TransferProgress?,
        isSelecting: Boolean = false,
        selected: Set<Long> = emptySet(),
        viewport: ListView,
    ): Boolean {
        val visibleTransfer = transfer?.takeIf { active ->
            newItems.any { row -> row.item.id == active.id && row.incoming == active.incoming && !row.delivered }
        }
        val minuteBucket = if (newItems.any { it.expiresAt != null }) System.currentTimeMillis() / 20_000L else 0L
        val sameRows = items == newItems && earlier == hasEarlier
        val samePresentation = sameRows && selecting == isSelecting &&
            selectedSeqs == selected && lastMinuteBucket == minuteBucket
        val previousProgress = progress
        if (samePresentation) {
            if (previousProgress == visibleTransfer) return false
            if (visibleTransfer != null && previousProgress?.id == visibleTransfer.id &&
                previousProgress.incoming == visibleTransfer.incoming
            ) {
                progress = visibleTransfer
                for (index in 0 until viewport.childCount) {
                    viewport.getChildAt(index).findViewWithTag<TransferProgressView>(PROGRESS_TAG)
                        ?.update(visibleTransfer)
                }
                return false
            }
        }
        if (!sameRows) {
            var previousDay: LocalDate? = null
            entries = buildList {
                if (hasEarlier) add(TimelineEntry.Earlier)
                for (row in newItems) {
                    if (!received) {
                        val day = Instant.ofEpochMilli(row.item.ts).atZone(ZoneId.systemDefault()).toLocalDate()
                        if (previousDay != day) add(TimelineEntry.Date(row.item.ts, day, row.seq))
                        previousDay = day
                    }
                    add(TimelineEntry.Message(row))
                }
            }
        }
        items = newItems
        earlier = hasEarlier
        progress = visibleTransfer
        selecting = isSelecting
        selectedSeqs = selected.toSet()
        lastMinuteBucket = minuteBucket
        notifyDataSetChanged()
        return true
    }

    fun positionOf(id: Long) = entries.indexOfFirst { it.stableId == id }

    override fun getCount() = entries.size
    override fun getItem(position: Int): Any = entries[position]
    override fun getItemId(position: Int) = entries[position].stableId
    override fun hasStableIds() = true

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val entry = entries[position]
        return when (entry) {
            TimelineEntry.Earlier -> centered(
                ui.action("Load earlier messages") { loadOlder() }.apply {
                    background = ui.tapShape(ui.surface, 22, ui.line)
                },
                vertical = 7,
            )
            is TimelineEntry.Date -> centered(
                ui.label(dateLabel(entry), 12, ui.dayInk, bold = true).apply {
                    background = ui.shape(ui.dayColor, 14)
                    setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4))
                    isAccessibilityHeading = true
                },
                vertical = 9,
            )
            is TimelineEntry.Message -> messageView(
                entry.row, parent.width,
                followsGroup = groupsWith(entry.row, entries.getOrNull(position - 1)),
                continuesGroup = groupsWith(entry.row, entries.getOrNull(position + 1)),
            )
        }.apply { tag = entry.stableId }
    }

    private fun dateLabel(entry: TimelineEntry.Date): String {
        val today = LocalDate.now()
        return when (entry.day) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> DateUtils.formatDateTime(
                context, entry.timestamp,
                DateUtils.FORMAT_SHOW_DATE or
                    (if (entry.day.year == today.year) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR),
            )
        }
    }

    private fun centered(view: View, vertical: Int) = FrameLayout(context).apply {
        setPadding(0, ui.dp(vertical), 0, ui.dp(vertical))
        addView(view, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
    }

    private fun groupsWith(message: ChatItem, adjacent: TimelineEntry?): Boolean {
        val other = (adjacent as? TimelineEntry.Message)?.row ?: return false
        return !received && other.incoming == message.incoming &&
            kotlin.math.abs(other.item.ts - message.item.ts) <= DateUtils.MINUTE_IN_MILLIS * 2
    }

    private fun messageView(
        message: ChatItem, parentWidth: Int, followsGroup: Boolean, continuesGroup: Boolean,
    ): View {
        val item = message.item
        val file = item.kind == "file"
        val largeText = item.textSize != null
        val isSelected = selecting && message.seq in selectedSeqs
        val peerLabel = store.peer()?.platformLabel() ?: "Computer"
        val openUri = if (file && message.incoming && message.delivered) message.downloadUri else null
        val open: (() -> Unit)? = when {
            selecting -> ({ toggleSelect(message) })
            openUri != null -> ({ openFile(message) })
            largeText && canRead(message) -> ({ showFullMessage(message) })
            else -> null
        }
        val active = progress?.takeIf { it.id == item.id && it.incoming == message.incoming }
        val reserveCheck = if (selecting) ui.dp(36) else 0
        val available = (if (parentWidth > 0) parentWidth else context.resources.displayMetrics.widthPixels) - ui.dp(22) - reserveCheck
        val maxWidth = (available * (if (received) 1f else 0.84f)).roundToInt().coerceAtLeast(ui.dp(108))
        val bubbleColor = if (!message.incoming && !received) ui.mint else ui.receivedBubble
        val metaColor = if (!message.incoming && !received) ui.sentMeta else ui.muted
        val bubble = ui.column().apply {
            minimumWidth = ui.dp(72)
            background = ui.tapBubble(
                bubbleColor, message.incoming || received, groupedEnd = !continuesGroup,
                border = if (isSelected) ui.accent else null, borderWidthDp = if (isSelected) 2 else 1,
            )
            elevation = ui.dp(1).toFloat()
            setPadding(ui.dp(12), ui.dp(7), ui.dp(11), ui.dp(5))
            if (selecting) {
                setOnClickListener { toggleSelect(message) }
                isFocusable = true
            } else {
                setOnLongClickListener {
                    showMessageMenu(this, message)
                    true
                }
                if (open != null) {
                    setOnClickListener { open() }
                    isFocusable = true
                }
            }
        }
        item.replyTo?.let { id ->
            val original = items.firstOrNull { it.item.id == id } ?: store.findItem(item.chatId, id)
            val quote = ui.column().apply {
                background = ui.shape(ui.replyColor, 10)
                setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6))
                addView(ui.label(original?.let { author(it, peerLabel) } ?: "Reply", 12, ui.accent, bold = true))
                addView(ui.label(original?.let(::previewOf) ?: "Original message unavailable", 13, ui.ink).apply {
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                    setMaxWidth(maxWidth - ui.dp(44))
                })
            }
            bubble.addView(quote, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = ui.dp(6) })
        }
        var compactText: TextView? = null
        if (file) {
            bubble.addView(fileHeader(message, openUri, metaColor), LinearLayout.LayoutParams(MATCH, WRAP))
        } else {
            val output = ui.label("", 15, ui.ink).apply {
                this.maxWidth = maxWidth - ui.dp(23)
                includeFontPadding = false
                setLineSpacing(ui.dp(2).toFloat(), 1f)
                text = when {
                    item.text != null -> rendered.get(message.seq) ?: ui.markup.render(item.text).also {
                        rendered.put(message.seq, it)
                    }
                    !canRead(message) -> "Receiving text message…"
                    else -> rendered.get(message.seq) ?: run {
                        val preview = try { store.previewText(message) } catch (_: Exception) { null }
                        val formatted = if (preview != null) ui.markup.render(preview) else "Large text message"
                        rendered.put(message.seq, formatted)
                        formatted
                    }
                }
                if (largeText) {
                    maxLines = 12
                    ellipsize = TextUtils.TruncateAt.END
                }
            }
            val preview = output.text
            if (!largeText && active == null && item.replyTo == null && message.error == null &&
                message.expiresAt == null && !selecting && '\n' !in preview &&
                output.paint.measureText(preview.toString()) < maxWidth - ui.dp(103)
            ) {
                compactText = output
            } else {
                bubble.addView(output, LinearLayout.LayoutParams(WRAP, WRAP))
            }
            if (largeText && canRead(message) && !selecting) {
                bubble.addWithGap(ui.label("Open message", 12, ui.accentText, bold = true), 4)
            }
        }
        if (active != null) {
            bubble.addWithGap(TransferProgressView(context, ui).apply { update(active) }, 5)
        }
        val shownTime = DateUtils.formatDateTime(
            context,
            item.ts,
            DateUtils.FORMAT_SHOW_TIME or
                (if (received) DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH else 0),
        )
        val state = when {
            message.error != null -> message.error
            active != null -> null
            message.incoming && !message.delivered -> "Receiving"
            message.incoming -> null
            message.delivered -> "✓✓"
            else -> "Queued"
        }
        val footer = ui.row().apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        message.expiresAt?.let { expires ->
            val badge = ui.label("⏱ ${formatRemainingLabel(expires)}", 11, ui.accent, bold = true).apply {
                background = ui.shape(ui.replyColor, 12, ui.line)
                setPadding(ui.dp(7), ui.dp(2), ui.dp(7), ui.dp(2))
            }
            footer.addView(badge, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = ui.dp(6) })
        }
        footer.addView(ui.label(listOfNotNull(shownTime, state).joinToString("  "), 11,
            if (message.error == null) metaColor else ui.errorColor,
        ).apply {
            this.maxWidth = maxWidth - ui.dp(22)
            gravity = Gravity.END
            if (!message.incoming && message.delivered) contentDescription = "Delivered at ${shownTime}"
        })
        if (compactText == null) {
            bubble.addView(footer, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(3) })
        } else {
            val inline = ui.row().apply { gravity = Gravity.BOTTOM }
            inline.addView(compactText, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                marginEnd = ui.dp(9)
                bottomMargin = ui.dp(2)
            })
            inline.addView(footer, LinearLayout.LayoutParams(WRAP, WRAP))
            bubble.addView(inline, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        val width = if (file || largeText) maxWidth else WRAP

        if (!selecting) {
            return FrameLayout(context).apply {
                setPadding(0, ui.dp(if (followsGroup) 1 else 5), 0, ui.dp(1))
                addView(bubble, FrameLayout.LayoutParams(
                    width, WRAP, if (message.incoming || received) Gravity.START else Gravity.END,
                ))
            }
        }

        val checkCircle = FrameLayout(context).apply {
            background = if (isSelected) ui.shape(ui.accent, 14) else ui.shape(android.graphics.Color.TRANSPARENT, 14, ui.muted, 2)
            if (isSelected) {
                addView(ui.symbol(R.drawable.ic_chat_check, ui.onAccent),
                    FrameLayout.LayoutParams(ui.dp(15), ui.dp(15), Gravity.CENTER))
            }
            contentDescription = if (isSelected) "Selected" else "Not selected"
        }
        val holder = FrameLayout(context).apply {
            addView(bubble, FrameLayout.LayoutParams(
                width, WRAP, if (message.incoming || received) Gravity.START else Gravity.END,
            ))
        }
        return ui.row().apply {
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
            background = if (isSelected) ui.shape(ui.selectedColor, 12) else null
            addView(checkCircle, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply { marginEnd = ui.dp(10) })
            addView(holder, LinearLayout.LayoutParams(0, WRAP, 1f))
            setOnClickListener { toggleSelect(message) }
        }
    }

    private fun fileHeader(row: ChatItem, openUri: Uri?, metaColor: Int): LinearLayout {
        val file = row.item
        val name = file.name ?: "Attachment"
        val extension = name.substringAfterLast('.', "").lowercase()
        val mime = resolveMimeType(name)
        val icon = when {
            extension == "apk" -> R.drawable.ic_chat_apk
            mime.startsWith("video/") -> R.drawable.ic_chat_movie
            !row.delivered -> if (row.incoming) R.drawable.ic_chat_download else R.drawable.ic_chat_upload
            else -> R.drawable.ic_chat_file
        }
        val body = ui.row()
        body.addView(ui.symbol(icon, ui.onAccent).apply {
            setPadding(ui.dp(11), ui.dp(11), ui.dp(11), ui.dp(11))
            background = ui.shape(ui.accent, 23)
        }, LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)).apply { marginEnd = ui.dp(10) })
        val details = ui.column()
        details.addView(ui.label(name, 15, ui.ink, bold = true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        val size = file.size?.let { Formatter.formatShortFileSize(context, it) }
        val type = extension.takeIf { it.length in 1..8 }?.uppercase()
        details.addView(ui.label(listOfNotNull(size, type).joinToString(" · "), 12, metaColor))
        body.addView(details, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (!selecting) {
            val menu = ui.iconAction(R.drawable.ic_chat_more, "Options for ${name}") {
                showMessageMenu(it, row)
            }
            body.addView(menu, LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)).apply { marginStart = ui.dp(4) })
        }
        if (openUri != null) body.contentDescription = "Open ${name}"
        return body
    }

}

private val PROGRESS_TAG = Any()

private class TransferProgressView(context: Context, private val ui: PortalViews) : LinearLayout(context) {
    private val meter = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progressTintList = ColorStateList.valueOf(ui.accent)
        progressBackgroundTintList = ColorStateList.valueOf(ui.line)
    }
    private val detail = ui.label("", 11, ui.muted).apply {
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
    }

    init {
        orientation = VERTICAL
        tag = PROGRESS_TAG
        addView(meter, LayoutParams(MATCH, ui.dp(5)))
        addWithGap(detail, 3)
    }

    fun update(transfer: TransferProgress) {
        val total = transfer.totalBytes.coerceAtLeast(0L)
        val done = transfer.completedBytes.coerceAtLeast(0L)
        val percent = if (total > 0L) (100.0 * done / total).roundToInt().coerceIn(0, 100) else 0
        meter.visibility = if (total > 0L) View.VISIBLE else View.GONE
        meter.setProgress(percent, isLaidOut)
        meter.contentDescription = "${percent}% transferred"
        val speed = if (transfer.bytesPerSecond > 0) {
            Formatter.formatShortFileSize(context, transfer.bytesPerSecond) + "/s"
        } else null
        val eta = transfer.etaSeconds?.let { value ->
            val unit = if (value >= 60) "${1 + (value - 1) / 60} min" else "${value} sec"
            "${unit} left"
        }
        val amount = if (total > 0L) {
            val sent = Formatter.formatShortFileSize(context, done)
            "${sent} of ${Formatter.formatShortFileSize(context, total)}"
        } else Formatter.formatShortFileSize(context, done)
        val percentText = if (total > 0L) "${percent}%" else null
        val summary = listOfNotNull(percentText, amount, speed, eta).joinToString(" · ")
        if (detail.text.toString() != summary) detail.text = summary
    }
}
