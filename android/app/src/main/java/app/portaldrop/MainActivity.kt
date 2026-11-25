package app.portaldrop

import android.Manifest
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.portaldrop.TransferService.Status
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.IOException
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private enum class Page { CHAT, CHATS, RECEIVED, MEDIA }

    private data class Draft(val text: String, val reply: ChatItem?)

    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var normalToolbar: LinearLayout
    private lateinit var selectionToolbar: LinearLayout
    private lateinit var selectionTitle: TextView
    private lateinit var selectionAll: TextView
    private lateinit var selectionDelete: View
    private lateinit var status: TextView
    private lateinit var headerTitle: TextView
    private lateinit var headerBack: View
    private lateinit var headerAvatar: FrameLayout
    private var avatarKey: Pair<String, String>? = null
    private lateinit var headerMark: ImageView
    private lateinit var menu: View
    private lateinit var store: PortalStore
    private var input: EditText? = null
    private var composer: EditText? = null
    private var pairError: TextView? = null
    private var pairButton: TextView? = null
    private var fileButton: View? = null
    private var tempButton: FrameLayout? = null
    private var tempInnerHalo: View? = null
    private var tempIconView: ImageView? = null
    private var tempBanner: LinearLayout? = null
    private var tempDetail: TextView? = null
    private var emptyState: TextView? = null
    private var receivedClear: TextView? = null
    private var messages: ChatTimelineAdapter? = null
    private var timelineList: ChatViewport? = null
    private var rooms: RoomAdapter? = null
    private var sharedMedia: SharedMediaView? = null
    private var replyArea: LinearLayout? = null
    private var attachmentReview: AlertDialog? = null
    private var hotspotDialog: AlertDialog? = null
    private var reconnecting = false

    private var page = Page.CHAT
    private var chatId = DEFAULT_CHAT_ID
    private var shownPeer: Boolean? = null
    private var shownFingerprint: String? = null
    private var addingComputer = false
    private var loadedFrom: Long? = null
    private var earlierThan: Long? = null
    private var chatSearch = ""
    private val drafts = mutableMapOf<String, Draft>()
    private var tempTtl: Long? = null
    private var selecting = false
    private val selectedSeqs = linkedSetOf<Long>()
    private var pairing = false
    private var stagingFiles = false
    private var queueingText = false
    private var saveLocationLabelView: TextView? = null
    private var saveLocationResetBtn: View? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val fileWorker = Executors.newSingleThreadExecutor()
    private val searchWorker = Executors.newSingleThreadExecutor()
    private val mediaWorker = Executors.newSingleThreadExecutor()
    private val updateWorker = Executors.newSingleThreadExecutor()
    private var checkingForUpdates = false
    private var mediaPreviews: MediaPreviews? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val expiryTick = object : Runnable {
        override fun run() {
            if (store.purgeExpired().isNotEmpty() || page == Page.CHAT || page == Page.MEDIA) {
                refresh()
            }
            mainHandler.postDelayed(this, 15_000L)
        }
    }

    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (addingComputer) {
                if (!pairing) {
                    addingComputer = false
                    refresh()
                }
                return
            }
            if (selecting) {
                exitSelection()
                return
            }
            switchPage(if (page == Page.CHAT) Page.CHATS else Page.CHAT)
        }
    }

    private val ui by lazy { PortalViews(this) }

    private val updates = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == OfflineHotspotService.ACTION_UPDATE) renderHotspotDialog() else refresh()
        }
    }

    private val scan = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { scanned ->
            input?.setText(scanned)
            pairError?.visibility = View.GONE
        }
    }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        reviewAttachments(uris)
    }

    private val pickSaveFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)
            val label = store.formatTreeLabel(uri)
            store.setSaveTreeUri(uri, label)
            saveLocationLabelView?.text = label
            saveLocationResetBtn?.visibility = View.VISIBLE
            notifyUser("Save location set to $label")
        } catch (_: Exception) {
            notifyUser("Could not save access to this folder. Try picking a folder inside internal storage or your SD card.")
        }
    }

    private val pickFolderToSend = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        tree?.let(::reviewFolder)
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (!it) notifyUser("Background connection is on. Allow notifications for message and file alerts.")
    }

    private val hotspotPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hotspotPermissionGranted()) {
            startOfflineHotspot()
        } else {
            val permission = if (Build.VERSION.SDK_INT >= 33) "Nearby devices" else "Precise location"
            notifyUser("Allow $permission for Portal Drop in Android Settings to start a hotspot.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PortalStore.get(this)
        page = Page.entries.firstOrNull { it.name == savedInstanceState?.getString("page") } ?: Page.CHAT
        onBackPressedDispatcher.addCallback(this, back)
        buildShell()
        refresh()
        if (savedInstanceState == null) consumeIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(TransferService.ACTION_UPDATE).apply {
            addAction(OfflineHotspotService.ACTION_UPDATE)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(updates, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(updates, filter)
        }
        if (store.backgroundEnabled() && store.peer() != null && TransferService.status == Status.PAUSED) {
            try {
                TransferService.start(this)
            } catch (_: IllegalStateException) {
                notifyUser("Could not resume the background connection.")
            }
        }
        store.purgeExpired()
        refresh()
        renderHotspotDialog()
        mainHandler.postDelayed(expiryTick, 15_000L)
    }

    override fun onStop() {
        mainHandler.removeCallbacks(expiryTick)
        unregisterReceiver(updates)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page.name)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        hotspotDialog?.dismiss()
        searchWorker.shutdownNow()
        mediaWorker.shutdownNow()
        updateWorker.shutdownNow()
        mediaPreviews?.close()
        worker.shutdown()
        fileWorker.shutdown()
        super.onDestroy()
    }

    private fun buildShell() {
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        root = ui.column().apply {
            setBackgroundColor(ui.canvasColor)
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime(),
            )
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }
        setContentView(root)

        normalToolbar = ui.row().apply {
            setBackgroundColor(ui.surface)
            setPadding(ui.dp(10), ui.dp(6), ui.dp(8), ui.dp(6))
        }
        headerBack = ui.iconAction(R.drawable.ic_chat_back, "Back") {
            back.handleOnBackPressed()
        }
        normalToolbar.addView(headerBack, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        headerMark = ImageView(this).apply {
            setImageResource(R.drawable.ic_portal_drop)
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        normalToolbar.addView(headerMark, LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)))
        headerAvatar = FrameLayout(this).apply { visibility = View.GONE }
        normalToolbar.addView(headerAvatar, LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)))
        val heading = ui.column().apply { setPadding(ui.dp(11), 0, ui.dp(6), 0) }
        headerTitle = ui.label("", 19, ui.ink, bold = true).apply {
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            isAccessibilityHeading = true
        }
        status = ui.label("", 12, ui.muted).apply {
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        heading.addView(headerTitle)
        heading.addView(status)
        normalToolbar.addView(heading, LinearLayout.LayoutParams(0, WRAP, 1f))
        menu = ui.iconAction(R.drawable.ic_chat_more, "Chat options") { showMenu(it) }
        normalToolbar.addView(menu, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        root.addView(normalToolbar, LinearLayout.LayoutParams(MATCH, WRAP))

        // Contextual multi-select action toolbar
        selectionToolbar = ui.row().apply {
            setBackgroundColor(ui.mint)
            setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
            visibility = View.GONE
        }
        selectionToolbar.addView(
            ui.iconAction(R.drawable.ic_chat_close, "Cancel selection", color = ui.ink) { exitSelection() },
            LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)),
        )
        selectionTitle = ui.label("0 selected", 17, ui.ink, bold = true).apply {
            setPadding(ui.dp(6), 0, ui.dp(6), 0)
        }
        selectionToolbar.addView(selectionTitle, LinearLayout.LayoutParams(0, WRAP, 1f))
        selectionAll = ui.action("Select all") { toggleSelectAll() }
        selectionToolbar.addView(selectionAll, LinearLayout.LayoutParams(WRAP, ui.dp(44)))
        selectionDelete = ui.iconAction(R.drawable.ic_chat_trash, "Delete selected", color = ui.errorColor) {
            confirmBatchDelete()
        }
        selectionToolbar.addView(selectionDelete, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        root.addView(selectionToolbar, LinearLayout.LayoutParams(MATCH, WRAP))

        root.addView(ui.rule())
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    private fun refresh(prepending: Boolean = false) {
        val peer = store.peer()
        val paired = peer != null && !addingComputer
        if (shownPeer != paired || shownFingerprint != peer?.fingerprint) {
            val changedDesktop = shownFingerprint != peer?.fingerprint
            shownPeer = paired
            shownFingerprint = peer?.fingerprint
            if (!paired || changedDesktop) {
                page = Page.CHAT
                drafts.clear()
                tempTtl = null
                selecting = false
                selectedSeqs.clear()
                loadedFrom = null
                earlierThan = null
            }
            if (paired) {
                chatId = store.activeChatId()
            }
            showPage()
        }
        if (page == Page.CHATS && selecting) {
            selecting = false
            selectedSeqs.clear()
        }
        normalToolbar.visibility = if (selecting) View.GONE else View.VISIBLE
        selectionToolbar.visibility = if (selecting) View.VISIBLE else View.GONE
        menu.contentDescription = if (paired) "Chat options" else "App options"
        headerBack.visibility = if (paired && page != Page.CHATS) View.VISIBLE else View.GONE
        headerMark.visibility = if (paired && page != Page.CHATS) View.GONE else View.VISIBLE
        headerAvatar.visibility = if (paired && page == Page.CHAT) View.VISIBLE else View.GONE
        back.isEnabled = addingComputer || selecting || (paired && page != Page.CHATS)
        val connection = when {
            !paired -> null
            !store.backgroundEnabled() -> "Paused"
            TransferService.status == Status.CONNECTED -> "Connected"
            TransferService.status == Status.CONNECTING -> "Connecting"
            TransferService.status == Status.UNPAIRED -> "Pair again"
            else -> "Offline"
        }
        val platform = peer?.platformLabel() ?: "Computer"
        val subtitle = when {
            !paired -> null
            page == Page.CHAT || page == Page.CHATS -> "$platform · ${connection.orEmpty()}"
            else -> connection
        }
        val statusText = subtitle.orEmpty()
        if (status.text.toString() != statusText) status.text = statusText
        status.visibility = if (subtitle == null) View.GONE else View.VISIBLE
        val statusColor = if (connection == "Connected") ui.accentText else ui.muted
        if (status.currentTextColor != statusColor) status.setTextColor(statusColor)
        if (!paired) {
            headerTitle.text = "Portal Drop"
            return
        }
        when (page) {
            Page.CHATS -> {
                headerTitle.text = "Chats"
                val chats = store.chats().filter { it.title.contains(chatSearch.trim(), ignoreCase = true) }
                rooms?.replace(chats, chatId)
                emptyState?.text = if (chatSearch.isBlank()) "Create a chat to start sending" else "No matching chats"
                emptyState?.visibility = if (chats.isEmpty()) View.VISIBLE else View.GONE
            }
            Page.RECEIVED -> {
                headerTitle.text = "Received files"
                val items = store.receivedFiles()
                updateTimeline(items, hasEarlier = false)
                receivedClear?.isEnabled = items.isNotEmpty()
                receivedClear?.alpha = if (items.isEmpty()) 0.5f else 1f
            }
            Page.MEDIA -> {
                headerTitle.text = "Shared Media"
                sharedMedia?.refresh()
            }
            Page.CHAT -> {
                val chats = store.chats()
                if (chats.none { it.id == chatId } && chats.isNotEmpty()) {
                    chatId = chats.first().id
                    store.setActiveChat(chatId)
                    selecting = false
                    selectedSeqs.clear()
                    loadedFrom = null
                    earlierThan = null
                }
                val title = chats.firstOrNull { it.id == chatId }?.title ?: "Chat"
                if (headerTitle.text.toString() != title) headerTitle.text = title
                updateHeaderAvatar(chatId, title)
                refreshChatTimeline(prepending)
            }
        }
        if (selecting) updateSelectionToolbar()
    }

    private fun updateHeaderAvatar(id: String, title: String) {
        val key = id to title
        if (avatarKey == key) return
        avatarKey = key
        headerAvatar.removeAllViews()
        headerAvatar.addView(ui.avatar(id, title, 38), FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun refreshChatTimeline(prepending: Boolean) {
        var floor = loadedFrom
        if (floor == null) {
            val latest = store.chatPage(chatId)
            val previousFirst = messages?.visibleItems()?.firstOrNull()?.seq
            val latestFirst = latest.items.firstOrNull()?.seq
            // Keep the previous page available when arrivals would push a user's anchor out of it.
            if (previousFirst == null || latestFirst == null || previousFirst >= latestFirst ||
                timelineList?.isAtLatest() != false
            ) {
                updateTimeline(latest.items, latest.olderThan != null, prepending)
                return
            }
            floor = previousFirst
            loadedFrom = previousFirst
            earlierThan = previousFirst.takeIf {
                store.chatPage(chatId, beforeSeq = it, limit = 1).items.isNotEmpty()
            }
        }
        updateTimeline(store.chatFrom(chatId, floor), earlierThan != null, prepending)
    }

    private fun showPage() {
        content.removeAllViews()
        input = null
        composer = null
        pairButton = null
        pairError = null
        fileButton = null
        tempButton = null
        tempInnerHalo = null
        tempIconView = null
        tempBanner = null
        tempDetail = null
        emptyState = null
        receivedClear = null
        messages = null
        timelineList = null
        rooms = null
        sharedMedia = null
        replyArea = null
        val view = if (shownPeer != true) pairingView() else when (page) {
            Page.CHATS -> chatsView()
            Page.CHAT, Page.RECEIVED -> deviceView()
            Page.MEDIA -> SharedMediaView(
                this, ui, store, chatId, previews(), mediaWorker, ::openSharedFile, ::openSharedLink,
            ).also { sharedMedia = it }
        }
        content.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun previews(): MediaPreviews = mediaPreviews ?: MediaPreviews(this, store).also { mediaPreviews = it }

    private fun pairingView(): View {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val form = ui.column().apply {
            setPadding(ui.dp(24), ui.dp(39), ui.dp(24), ui.dp(26))
        }
        val illustration = ImageView(this).apply {
            setImageResource(R.drawable.ic_portal_drop)
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        form.addView(illustration, LinearLayout.LayoutParams(ui.dp(72), ui.dp(72)))
        form.addWithGap(ui.label("Pair your computer", 30, ui.ink, bold = true), 16)
        form.addWithGap(ui.label("Open Pair Android in Portal Drop on Linux or Windows, or run this command.", 16, ui.muted), 10)
        form.addWithGap(ui.label("portal-drop pair", 15, ui.ink, bold = true).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            background = ui.shape(ui.surface, 14, ui.line)
            setPadding(ui.dp(15), ui.dp(12), ui.dp(15), ui.dp(12))
        }, 16)
        val scanButton = ui.action("Scan QR code") {
            scan.launch(ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Scan the Portal Drop invite")
                .setBeepEnabled(false)
                .setOrientationLocked(false))
        }.apply { background = ui.tapShape(ui.surface, 15, ui.line) }
        form.addWithGap(scanButton, 16)
        form.addWithGap(ui.label("Pairing link", 13, ui.ink, bold = true), 23)

        val linkRow = ui.row()
        input = ui.entry(
            "portaldrop://…",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
        ).apply {
            maxLines = 3
            filters = arrayOf(InputFilter.LengthFilter(1024))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            isSaveEnabled = false
        }
        linkRow.addView(input, LinearLayout.LayoutParams(0, WRAP, 1f))
        linkRow.addView(ui.action("Paste") {
            val clipboard = getSystemService(ClipboardManager::class.java)
            val clip = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)
            if (clip.isNullOrBlank()) notifyUser("Copy the pairing link from your computer first.")
            else input?.setText(clip)
        }, LinearLayout.LayoutParams(WRAP, ui.dp(48)).apply { marginStart = ui.dp(7) })
        form.addWithGap(linkRow, 6)
        pairError = ui.label("", 14, ui.errorColor).apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        form.addWithGap(pairError!!, 8)
        pairButton = ui.action("Pair & enable background", primary = true) {
            pair(input?.text.toString())
        }
        form.addWithGap(pairButton!!, 13)
        form.addWithGap(ui.label("Paired devices receive files automatically while background connection is on.", 13, ui.muted), 11)
        form.isFocusableInTouchMode = true
        form.requestFocus()
        scroll.addView(form, FrameLayout.LayoutParams(MATCH, WRAP))
        return scroll
    }

    private fun switchPage(destination: Page) {
        exitSelection(refreshUi = false)
        saveDraft()
        hideKeyboard()
        page = destination
        showPage()
        refresh()
    }

    private fun activateChat(id: String) {
        exitSelection(refreshUi = false)
        saveDraft()
        hideKeyboard()
        if (chatId != id) {
            chatId = id
            loadedFrom = null
            earlierThan = null
        }
        store.setActiveChat(id)
        page = Page.CHAT
        showPage()
        refresh()
    }

    // === Batch Multi-Select Deletion ===
    private fun enterSelection(initial: ChatItem? = null) {
        selecting = true
        selectedSeqs.clear()
        if (initial != null) selectedSeqs.add(initial.seq)
        hideKeyboard()
        refresh()
    }

    private fun exitSelection(refreshUi: Boolean = true) {
        if (!selecting && selectedSeqs.isEmpty()) return
        selecting = false
        selectedSeqs.clear()
        if (refreshUi) refresh()
    }

    private fun toggleSelect(row: ChatItem) {
        if (!selecting) {
            enterSelection(row)
            return
        }
        if (!selectedSeqs.remove(row.seq)) selectedSeqs.add(row.seq)
        refresh()
    }

    private fun toggleSelectAll() {
        val visible = messages?.visibleItems().orEmpty()
        val allSeqs = visible.map(ChatItem::seq).toSet()
        if (allSeqs.isNotEmpty() && selectedSeqs.containsAll(allSeqs)) {
            selectedSeqs.clear()
        } else {
            selectedSeqs.clear()
            selectedSeqs.addAll(allSeqs)
        }
        refresh()
    }

    private fun updateSelectionToolbar() {
        val visible = messages?.visibleItems().orEmpty()
        val valid = visible.map(ChatItem::seq).toSet()
        selectedSeqs.retainAll(valid)
        val count = selectedSeqs.size
        selectionTitle.text = "$count selected"
        selectionAll.text = if (count > 0 && count == valid.size) "None" else "All"
        selectionDelete.isEnabled = count > 0
        selectionDelete.alpha = if (count > 0) 1f else 0.4f
    }

    private fun confirmBatchDelete() {
        val chosen = messages?.visibleItems().orEmpty().filter { it.seq in selectedSeqs }
        if (chosen.isEmpty()) return
        val files = chosen.count { it.item.kind == "file" }
        val texts = chosen.size - files
        val receivedOnDisk = chosen.count { it.item.kind == "file" && it.incoming && it.downloadUri != null }
        val summaryParts = buildList {
            if (texts > 0) add("$texts message${if (texts != 1) "s" else ""}")
            if (files > 0) add("$files file${if (files != 1) "s" else ""}")
        }
        val summary = summaryParts.joinToString(" and ").replaceFirstChar { it.uppercase() }
        val body = ui.column().apply {
            setPadding(ui.dp(22), ui.dp(10), ui.dp(22), ui.dp(4))
            addView(ui.label(
                "$summary will be removed from this device’s chat history." +
                    if (receivedOnDisk > 0) " Saved downloads remain in Downloads unless checked below." else "",
                14, ui.muted,
            ))
        }
        val checkBox = if (receivedOnDisk > 0) {
            CheckBox(this).apply {
                text = "Also delete $receivedOnDisk received file${if (receivedOnDisk != 1) "s" else ""} from Downloads"
                setTextColor(ui.ink)
                textSize = 14f
                buttonTintList = ColorStateList.valueOf(ui.accent)
            }.also { body.addWithGap(it, 12) }
        } else null

        AlertDialog.Builder(this)
            .setTitle("Delete ${chosen.size} item${if (chosen.size != 1) "s" else ""}?")
            .setView(body)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete (${chosen.size})") { _, _ ->
                val deleteFiles = checkBox?.isChecked == true
                val targets = chosen.map { Triple(it.item.chatId, it.item.id, it.incoming) }
                store.deleteItems(targets, deleteDownloadedFiles = deleteFiles)
                                exitSelection(refreshUi = false)
                refresh()
            }
            .show()
    }

    private fun saveDraft() {
        val editor = composer ?: return
        drafts[chatId] = Draft(editor.text.toString(), drafts[chatId]?.reply)
    }

    private fun currentChat() = store.chats().firstOrNull { it.id == chatId }

    private fun chatsView(): View {
        val screen = ui.column().apply { setBackgroundColor(ui.canvasColor) }
        val search = ui.row().apply {
            background = ui.shape(ui.inputColor, 24)
            setPadding(ui.dp(14), 0, ui.dp(8), 0)
            addView(ui.symbol(R.drawable.ic_chat_search, ui.muted),
                LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)))
            val field = ui.entry("Search chats", InputType.TYPE_CLASS_TEXT).apply {
                setSingleLine(true)
                setText(chatSearch)
                minHeight = ui.dp(44)
                setPadding(ui.dp(10), ui.dp(8), ui.dp(6), ui.dp(8))
                background = null
                doAfterTextChanged {
                    chatSearch = it.toString()
                    refresh()
                }
            }
            addView(field, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        screen.addView(search, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            marginStart = ui.dp(12)
            marginEnd = ui.dp(12)
            topMargin = ui.dp(8)
            bottomMargin = ui.dp(6)
        })
        screen.isFocusableInTouchMode = true
        screen.requestFocus()
        val panel = FrameLayout(this)
        val list = ListView(this).apply {
            divider = null
            isVerticalScrollBarEnabled = false
            setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(75))
            clipToPadding = false
            rooms = RoomAdapter(this@MainActivity, ui, ::activateChat, ::showChatMenu)
            adapter = rooms
            setSelector(android.R.color.transparent)
        }
        panel.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
        emptyState = centeredHint("Create a chat to start sending")
        panel.addView(emptyState, FrameLayout.LayoutParams(MATCH, MATCH))
        val new = ui.iconAction(R.drawable.ic_chat_plus, "New chat", fill = ui.accent, color = ui.onAccent) {
            newChat()
        }.apply { elevation = ui.dp(5).toFloat() }
        panel.addView(new, FrameLayout.LayoutParams(ui.dp(52), ui.dp(52), Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = ui.dp(17)
            bottomMargin = ui.dp(16)
        })
        screen.addView(panel, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return screen
    }

    private fun showChatMenu(anchor: View, room: ChatRoom) {
        val canDelete = store.chats().size > 1
        PopupMenu(this, anchor).apply {
            menu.add(0, CHAT_MENU_RENAME, 0, "Rename chat")
            if (canDelete) menu.add(0, CHAT_MENU_DELETE, 1, "Delete chat")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    CHAT_MENU_RENAME -> renameChat(room)
                    CHAT_MENU_DELETE -> confirmDeleteChat(room)
                }
                true
            }
            show()
        }
    }

    private fun newChat() {
        try {
            val room = store.createChat()
            TransferService.wake()
            activateChat(room.id)
        } catch (_: RuntimeException) {
            notifyUser("Could not create the chat.")
        }
    }

    private fun renameChat(room: ChatRoom) {
        val field = ui.entry("Chat name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES).apply {
            setSingleLine(true)
            setText(room.title)
            selectAll()
        }
        val holder = ui.column().apply {
            setPadding(ui.dp(20), ui.dp(10), ui.dp(20), 0)
            addView(field, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Rename chat")
            .setView(holder)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    store.renameChat(room.id, field.text.toString())
                    TransferService.wake()
                    dialog.dismiss()
                    refresh()
                } catch (error: IllegalArgumentException) {
                    field.error = error.message ?: "Enter a chat name."
                } catch (_: RuntimeException) {
                    notifyUser("Could not rename the chat.")
                }
            }
        }
        dialog.show()
    }

    private fun confirmDeleteChat(room: ChatRoom) {
        if (store.chats().size <= 1) {
            notifyUser("Portal Drop keeps at least one chat.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete “${room.title}”?")
            .setMessage("This removes the chat and its messages from both devices. Already downloaded files stay in Downloads.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete chat") { _, _ ->
                try {
                    val fallback = store.deleteChat(room.id)
                    drafts.remove(room.id)
                    if (chatId == room.id) {
                        chatId = fallback
                        loadedFrom = null
                        earlierThan = null
                        }
                    TransferService.wake()
                    refresh()
                } catch (error: IllegalArgumentException) {
                    notifyUser(error.message ?: "Could not delete the chat.")
                } catch (_: RuntimeException) {
                    notifyUser("Could not delete the chat.")
                }
            }
            .show()
    }

    private fun deviceView(): View {
        val screen = ui.column().apply {
            if (page == Page.CHAT) background = ChatBackdrop(this@MainActivity)
            else setBackgroundColor(ui.canvasColor)
        }
        if (page == Page.RECEIVED) {
            val controls = ui.row().apply {
                setPadding(ui.dp(12), 0, ui.dp(10), 0)
                setBackgroundColor(ui.surface)
            }
            receivedClear = ui.action("Clear history") { clearHistory() }
            controls.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            controls.addView(receivedClear, LinearLayout.LayoutParams(WRAP, ui.dp(48)))
            screen.addView(controls, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        val timeline = FrameLayout(this)
        val list = ChatViewport(this, tracksLatest = page == Page.CHAT,
            onSwipeReply = if (page == Page.CHAT) ::replyTo else null,
        ).apply {
            divider = null
            dividerHeight = 0
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(7))
            setSelector(android.R.color.transparent)
            if (page == Page.CHAT) {
                setStackFromBottom(true)
                transcriptMode = ListView.TRANSCRIPT_MODE_DISABLED
            }
            messages = ChatTimelineAdapter(
                this@MainActivity, ui, store, previews(), page == Page.RECEIVED,
                ::loadOlder, ::openFile, ::showFullMessage, ::showMessageMenu, ::toggleSelect, ::controlTransfer,
            )
            adapter = messages
        }
        timelineList = list
        timeline.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
        emptyState = centeredHint(
            if (page == Page.RECEIVED) "No received files yet" else "Send a message or attach files",
        )
        if (page == Page.CHAT) {
            emptyState?.apply {
                background = ui.shape(ui.dayColor, 18)
                setTextColor(ui.dayInk)
            }
            timeline.addView(emptyState, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        } else {
            timeline.addView(emptyState, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        if (page == Page.CHAT) {
            val latest = ui.iconAction(R.drawable.ic_chat_down, "Jump to latest", fill = ui.surface) {
                list.showLatest()
            }.apply {
                visibility = View.GONE
                elevation = ui.dp(2).toFloat()
            }
            list.setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView, scrollState: Int) = Unit

                override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
                    latest.visibility = if (visible > 0 && !list.isAtLatest()) View.VISIBLE else View.GONE
                }
            })
            timeline.addView(latest, FrameLayout.LayoutParams(ui.dp(44), ui.dp(44),
                Gravity.BOTTOM or Gravity.END).apply {
                marginEnd = ui.dp(14)
                bottomMargin = ui.dp(10)
            })
        }
        screen.addView(timeline, LinearLayout.LayoutParams(MATCH, 0, 1f))
        if (page == Page.CHAT) {
            screen.addView(composerView(), LinearLayout.LayoutParams(MATCH, WRAP))
        }
        return screen
    }

    private fun centeredHint(message: String) = ui.label(message, 15, ui.muted).apply {
        gravity = Gravity.CENTER
        setPadding(ui.dp(22), ui.dp(14), ui.dp(22), ui.dp(14))
    }

    private fun updateTimeline(items: List<ChatItem>, hasEarlier: Boolean, prepending: Boolean = false) {
        val list = timelineList ?: return
        val adapter = messages ?: return
        list.updateContent(prepending) {
            adapter.replace(items, hasEarlier, TransferService.progress, selecting, selectedSeqs, list)
        }
        emptyState?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun loadOlder() {
        val before = if (loadedFrom == null) store.chatPage(chatId).olderThan else earlierThan
        if (before == null) return
        val older = store.chatPage(chatId, beforeSeq = before)
        loadedFrom = older.items.firstOrNull()?.seq ?: loadedFrom ?: before
        earlierThan = older.olderThan
        refresh(prepending = true)
    }

    private fun composerView(): View {
        val composerPanel = ui.column().apply {
            setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(9))
        }
        val card = ui.column().apply {
            background = ui.shape(ui.surface, 18, ui.line)
            elevation = ui.dp(2).toFloat()
            setPadding(ui.dp(3), ui.dp(2), ui.dp(3), ui.dp(2))
        }
        replyArea = ui.row()
        card.addView(replyArea, LinearLayout.LayoutParams(MATCH, WRAP))
        fillReplyArea()

        tempBanner = ui.row().apply {
            background = ui.shape(ui.mint, 13, ui.line)
            setPadding(ui.dp(12), ui.dp(3), ui.dp(4), ui.dp(3))
            addView(ui.symbol(R.drawable.ic_chat_timer, ui.accent),
                LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply { marginEnd = ui.dp(8) })
            val detail = ui.label("", 12, ui.accent, bold = true).apply {
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            }
            tempDetail = detail
            addView(detail, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(ui.iconAction(R.drawable.ic_chat_close, "Turn off temporary message") {
                setTempTimer(null)
            }, LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)))
        }
        card.addView(tempBanner, LinearLayout.LayoutParams(MATCH, WRAP))

        val controls = ui.row()
        controls.gravity = Gravity.BOTTOM
        controls.setPadding(ui.dp(2), ui.dp(2), ui.dp(3), ui.dp(2))
        fileButton = ui.iconAction(R.drawable.ic_chat_clip, "Attach files", color = ui.muted) {
            pickFiles.launch(arrayOf("*/*"))
        }.apply {
            isEnabled = !stagingFiles
            alpha = if (stagingFiles) 0.45f else 1f
        }
        controls.addView(fileButton, LinearLayout.LayoutParams(ui.dp(42), ui.dp(46)))

        // Crisp 38dp inner mint halo inside 42x46dp touch target so armed state has balanced padding
        val halo = View(this)
        val timerImg = ui.symbol(R.drawable.ic_chat_timer, ui.muted)
        tempInnerHalo = halo
        tempIconView = timerImg
        tempButton = FrameLayout(this).apply {
            contentDescription = "Choose a temporary message timer"
            isFocusable = true
            addView(halo, FrameLayout.LayoutParams(ui.dp(38), ui.dp(38), Gravity.CENTER))
            addView(timerImg, FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER))
            setOnClickListener { chooseTempTimer() }
        }
        controls.addView(tempButton, LinearLayout.LayoutParams(ui.dp(42), ui.dp(46)).apply {
            marginEnd = ui.dp(2)
        })
        updateTempUi()

        composer = ui.entry(
            "Message",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
        ).apply {
            setText(drafts[chatId]?.text.orEmpty())
            maxLines = 6
            minHeight = ui.dp(46)
            setPadding(ui.dp(6), ui.dp(11), ui.dp(8), ui.dp(9))
            background = null
        }
        controls.addView(composer, LinearLayout.LayoutParams(0, WRAP, 1f))
        controls.addView(ui.iconAction(R.drawable.ic_chat_send, "Send", fill = ui.accent, color = ui.onAccent) {
            send()
        }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)).apply { bottomMargin = ui.dp(1) })
        card.addView(controls, LinearLayout.LayoutParams(MATCH, WRAP))
        composerPanel.addView(card, LinearLayout.LayoutParams(MATCH, WRAP))
        return composerPanel
    }

    private fun chooseTempTimer() {
        val choices = listOf(null) + TEMP_TIMERS.keys
        val labels = choices.map { TEMP_TIMERS[it] ?: "Off" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Temporary message timer")
            .setSingleChoiceItems(labels, choices.indexOf(tempTtl)) { dialog, index ->
                setTempTimer(choices[index])
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setTempTimer(ttl: Long?) {
        tempTtl = ttl
        updateTempUi()
    }

    private fun updateTempUi() {
        val armed = tempTtl != null
        tempDetail?.text = tempTtl?.let { "Temporary message · Expires in ${TEMP_TIMERS.getValue(it)}" }
        tempBanner?.visibility = if (armed) View.VISIBLE else View.GONE
        tempInnerHalo?.background = if (armed) ui.tapShape(ui.mint, 19, ui.accent)
        else ui.tapShape(android.graphics.Color.TRANSPARENT, 19)
        tempIconView?.imageTintList = ColorStateList.valueOf(if (armed) ui.accent else ui.muted)
    }

    private fun fillReplyArea() {
        val strip = replyArea ?: return
        strip.removeAllViews()
        val reply = drafts[chatId]?.reply
        strip.visibility = if (reply == null) View.GONE else View.VISIBLE
        if (reply == null) return
        val peerLabel = store.peer()?.platformLabel() ?: "Computer"
        strip.background = ui.shape(ui.replyColor, 10)
        strip.setPadding(ui.dp(10), 0, ui.dp(2), 0)
        strip.addView(ui.symbol(R.drawable.ic_chat_reply, ui.accent),
            LinearLayout.LayoutParams(ui.dp(23), ui.dp(23)).apply { marginEnd = ui.dp(8) })
        val preview = ui.column()
        preview.addView(ui.label("Reply to ${author(reply, peerLabel)}", 12, ui.accent, bold = true))
        preview.addView(ui.label(previewOf(reply), 13, ui.ink).apply {
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        })
        strip.addView(preview, LinearLayout.LayoutParams(0, WRAP, 1f))
        strip.addView(ui.iconAction(R.drawable.ic_chat_close, "Cancel reply") {
            saveDraft()
            drafts[chatId] = Draft(composer?.text.toString(), null)
            fillReplyArea()
        }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
    }

    private fun pair(link: String) {
        if (pairing) return
        if (link.isBlank()) {
            showPairError("Scan the QR code or paste the pairing link.")
            return
        }
        pairing = true
        pairButton?.isEnabled = false
        pairButton?.text = "Pairing…"
        pairError?.visibility = View.GONE
        hideKeyboard()
        worker.execute {
            var pairedPeer: Peer? = null
            val error = try {
                pairedPeer = PortalClient.pair(applicationContext, link)
                null
            } catch (issue: IllegalArgumentException) {
                issue.message ?: "The pairing invite is invalid."
            } catch (issue: IOException) {
                issue.message ?: "Could not pair. Check your computer service and try again."
            } catch (_: Exception) {
                "Could not pair. Try a fresh invite."
            }
            onUi {
                pairing = false
                pairButton?.isEnabled = true
                pairButton?.text = "Pair & enable background"
                if (error == null) {
                    addingComputer = false
                    pairedPeer?.let { TransferService.selectDesktop(this, it.fingerprint) }
                    refresh()
                    enableBackground()
                } else showPairError(error)
            }
        }
    }

    private fun showPairError(message: String) {
        pairError?.visibility = View.VISIBLE
        pairError?.text = message
    }

    private fun enableBackground() {
        try {
            TransferService.start(this)
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (_: RuntimeException) {
            notifyUser("Could not start the background connection. Try Resume.")
        }
        refresh()
    }

    private fun send() {
        val field = composer ?: return
        val text = field.text.toString()
        if (text.isBlank()) return
        val destination = chatId
        val sendingTimeline = timelineList
        val reply = drafts[destination]?.reply?.item?.id
        val ttlSec = tempTtl
        if (queueingText) return
        queueingText = true
        if (ttlSec != null) setTempTimer(null)
        worker.execute {
            val error = try {
                store.enqueueText(text, destination, reply, ttlSec)
                TransferService.wakeMessages()
                null
            } catch (issue: IllegalArgumentException) {
                issue.message ?: "This message could not be queued."
            } catch (_: Exception) {
                "This message could not be queued."
            }
            onUi {
                queueingText = false
                if (error == null) {
                    if (drafts[destination]?.text == text) drafts.remove(destination)
                    if (composer === field && chatId == destination && field.text.toString() == text) {
                        drafts.remove(destination)
                        field.text.clear()
                        fillReplyArea()
                    }
                    refreshAfterSend(destination, sendingTimeline)
                } else {
                    if (ttlSec != null && chatId == destination && tempTtl == null) setTempTimer(ttlSec)
                    notifyUser(error)
                }
            }
        }
    }

    private fun consumeIntent(request: Intent) {
        if (request.action == OfflineHotspotService.ACTION_OPEN) {
            setIntent(Intent(this, MainActivity::class.java))
            showOfflineHotspot()
            return
        }
        val target = request.getStringExtra(TransferService.EXTRA_DESKTOP)
        if (target != null && target != store.peer()?.fingerprint) {
            if (!canSwitchDesktop() || !TransferService.selectDesktop(this, target)) return
            addingComputer = false
            refresh()
        }
        request.getStringExtra(TransferService.EXTRA_CHAT_ID)
            ?.takeIf { id -> store.chats().any { it.id == id } }
            ?.let(::activateChat)
        if (request.action != Intent.ACTION_SEND && request.action != Intent.ACTION_SEND_MULTIPLE) return
        setIntent(Intent(this, MainActivity::class.java))
        if (store.peer() == null) {
            notifyUser("Pair your computer before sharing to Portal Drop.")
            return
        }
        val uris = try {
            incomingStreams(request)
        } catch (_: RuntimeException) {
            notifyUser("Portal Drop could not read the shared items.")
            return
        }
        if (uris.isNotEmpty()) {
            reviewAttachments(uris)
        } else {
            request.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeUnless(String::isBlank)?.let { text ->
                activateChat(store.activeChatId())
                val field = composer ?: return@let
                if (field.text.isNotBlank()) {
                    notifyUser("Send or clear the current draft before sharing new text.")
                } else {
                    field.setText(text)
                    field.setSelection(field.length())
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun incomingStreams(request: Intent): List<Uri> = buildList {
        if (Build.VERSION.SDK_INT >= 33) {
            if (request.action == Intent.ACTION_SEND_MULTIPLE) {
                request.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(::addAll)
            } else {
                request.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
            }
        } else if (request.action == Intent.ACTION_SEND_MULTIPLE) {
            request.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(::addAll)
        } else {
            request.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(::add)
        }
        request.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(::add)
        }
    }.filter { it.scheme == "content" }.distinct()

    private fun reviewAttachments(selected: List<Uri>) {
        val uris = selected.filter { it.scheme == "content" }.distinct()
        if (uris.isEmpty()) return
        if (store.peer() == null || stagingFiles || attachmentReview != null) {
            notifyUser("Finish the current file selection before adding more files.")
            return
        }
        val destination = if (page == Page.CHAT) chatId else store.activeChatId()
        val originalPeer = store.peer()?.fingerprint ?: return
        val title = store.chats().firstOrNull { it.id == destination }?.title ?: "Chat"
        val reply = drafts[destination]?.reply?.item?.id
        val ttlSec = tempTtl
        stagingFiles = true
        updateFileButton()
        worker.execute {
            val names = uris.map { uri ->
                try {
                    store.attachmentName(uri)
                } catch (_: Exception) {
                    "Attachment"
                }
            }
            onUi {
                stagingFiles = false
                updateFileButton()
                if (store.peer()?.fingerprint == originalPeer && !isFinishing) {
                    showAttachmentReview(uris, names, destination, title, reply, originalPeer, ttlSec)
                }
            }
        }
    }

    private fun showAttachmentReview(
        uris: List<Uri>, names: List<String>, destination: String, title: String,
        reply: String?, peer: String, ttlSec: Long?,
    ) {
        val body = ui.column().apply {
            setPadding(ui.dp(22), ui.dp(10), ui.dp(22), ui.dp(6))
            val subtitle = if (ttlSec != null) "Send to $title · Expires in ${TEMP_TIMERS.getValue(ttlSec)}" else "Send to $title"
            addView(ui.label(subtitle, 14, if (ttlSec != null) ui.accent else ui.muted))
            names.forEach { name ->
                val file = ui.row()
                file.addView(ui.symbol(R.drawable.ic_chat_file, ui.accent), LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)))
                file.addView(ui.label(name, 15, ui.ink).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = ui.dp(10) })
                addWithGap(file, 12)
            }
        }
        val caption = if (uris.size == 1) "Send file" else "Send ${uris.size} files"
        val scroll = ScrollView(this).apply {
            addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Review attachments")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(caption) { _, _ -> enqueueAttachments(uris, destination, reply, peer, ttlSec) }
            .create()
        dialog.setOnDismissListener { if (attachmentReview === dialog) attachmentReview = null }
        attachmentReview = dialog
        dialog.show()
    }

    private fun pickFolder() {
        if (stagingFiles || attachmentReview != null) {
            notifyUser("Finish the current file selection first.")
            return
        }
        try {
            pickFolderToSend.launch(null)
        } catch (_: ActivityNotFoundException) {
            notifyUser("No folder picker is available on this phone.")
        }
    }

    private fun reviewFolder(tree: Uri) {
        if (tree.scheme != "content" || !DocumentsContract.isTreeUri(tree) ||
            stagingFiles || attachmentReview != null
        ) return
        val peer = store.peer()?.fingerprint ?: return
        val destination = if (page == Page.CHAT) chatId else store.activeChatId()
        val title = store.chats().firstOrNull { it.id == destination }?.title ?: "Chat"
        val reply = drafts[destination]?.reply?.item?.id
        val ttlSec = tempTtl
        stagingFiles = true
        updateFileButton()
        fileWorker.execute {
            val folder = runCatching { store.folderName(tree) }
            onUi {
                stagingFiles = false
                updateFileButton()
                if (isFinishing || store.peer()?.fingerprint != peer) return@onUi
                val name = folder.getOrElse { error ->
                    val reason = (error as? IllegalArgumentException)?.message
                    notifyUser(reason ?: "Could not read the selected folder.")
                    return@onUi
                }
                val body = ui.column().apply {
                    setPadding(ui.dp(22), ui.dp(10), ui.dp(22), ui.dp(8))
                    addView(ui.label(name, 17, ui.ink, bold = true))
                    addWithGap(ui.label("Send to ${title}", 14, ui.muted), 5)
                    addWithGap(ui.label("Includes files in this folder and its subfolders.", 14, ui.muted), 13)
                }
                val dialog = AlertDialog.Builder(this)
                    .setTitle("Review folder")
                    .setView(body)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Send folder") { _, _ ->
                        importIntoChat(destination, reply, peer, ttlSec, "Preparing folder…") { onQueued ->
                            val count = store.enqueueFolder(tree, destination, reply, ttlSec)
                            if (count > 0) onQueued()
                            BatchEnqueueResult(count, emptyList())
                        }
                    }.create()
                dialog.setOnDismissListener { if (attachmentReview === dialog) attachmentReview = null }
                attachmentReview = dialog
                dialog.show()
            }
        }
    }

    private fun enqueueAttachments(
        uris: List<Uri>, destination: String, reply: String?, peer: String, ttlSec: Long?,
    ) {
        val notice = if (uris.size == 1) "Preparing file…" else "Adding ${uris.size} files…"
        importIntoChat(destination, reply, peer, ttlSec, notice) { onQueued ->
            store.enqueueFiles(
                uris = uris,
                chatId = destination,
                replyTo = reply,
                ttlSec = ttlSec,
                onFileQueued = onQueued,
            )
        }
    }

    private fun importIntoChat(
        destination: String, reply: String?, peer: String, ttlSec: Long?, notice: String,
        enqueue: (() -> Unit) -> BatchEnqueueResult,
    ) {
        if (stagingFiles || store.peer()?.fingerprint != peer) return
        val sendingTimeline = timelineList?.takeIf { page == Page.CHAT && chatId == destination }
        stagingFiles = true
        updateFileButton()
        if (ttlSec != null && tempTtl == ttlSec) setTempTimer(null)
        notifyUser(notice)
        fileWorker.execute {
            var draftCleared = false
            val result = try {
                if (store.peer()?.fingerprint != peer) throw IOException("The paired computer changed.")
                enqueue {
                    TransferService.wake()
                    onUi {
                        if (!draftCleared) {
                            draftCleared = true
                            val draft = drafts[destination]
                            if (draft?.reply?.item?.id == reply && draft != null) {
                                val currentText = if (destination == chatId) composer?.text?.toString() else null
                                drafts[destination] = Draft(currentText ?: draft.text, null)
                            }
                            fillReplyArea()
                        }
                        refreshAfterSend(destination, sendingTimeline)
                    }
                }
            } catch (error: Exception) {
                BatchEnqueueResult(0, listOf("Files" to (error.message ?: "Check access and available storage.")))
            }
            onUi {
                stagingFiles = false
                updateFileButton()
                if (result.queued > 0) {
                    if (result.failed.isNotEmpty()) {
                        val first = result.failed.first()
                        notifyUser("Added ${result.queued} file(s); ${result.failed.size} failed (${first.first}: ${first.second}).")
                    }
                    refreshAfterSend(destination, sendingTimeline)
                } else {
                    if (ttlSec != null && chatId == destination && tempTtl == null) setTempTimer(ttlSec)
                    val reason = result.failed.firstOrNull()?.second ?: "Check access and available storage."
                    notifyUser("Could not add files: $reason")
                }
            }
        }
    }

    private fun refreshAfterSend(destination: String, sendingTimeline: ChatViewport?) {
        refresh()
        if (page == Page.CHAT && chatId == destination && timelineList === sendingTimeline) {
            sendingTimeline?.showLatest()
        }
    }

    private fun updateFileButton() {
        fileButton?.isEnabled = !stagingFiles
        fileButton?.alpha = if (stagingFiles) 0.45f else 1f
    }

    private fun showMessageSearch() {
        val body = ui.column().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0) }
        val field = ui.entry("Search message text or filenames", InputType.TYPE_CLASS_TEXT).apply {
            filters = arrayOf(InputFilter.LengthFilter(256))
        }
        val results = ui.column()
        val scroller = ScrollView(this).apply { addView(results) }
        body.addView(field, LinearLayout.LayoutParams(MATCH, WRAP))
        body.addView(scroller, LinearLayout.LayoutParams(MATCH, ui.dp(350)))
        val dialog = AlertDialog.Builder(this)
            .setTitle("Search messages")
            .setView(body)
            .setNegativeButton("Close", null)
            .create()
        var pending: Runnable? = null
        var running: java.util.concurrent.Future<*>? = null
        var generation = 0
        fun cancelSearch() {
            generation++
            pending?.let(mainHandler::removeCallbacks)
            running?.cancel(true)
        }
        field.doAfterTextChanged { edit ->
            cancelSearch()
            results.removeAllViews()
            val query = edit?.toString()?.trim().orEmpty()
            if (query.isEmpty()) return@doAfterTextChanged
            val request = generation
            results.addView(ui.label("Searching…", 14, ui.muted))
            pending = Runnable {
                if (isDestroyed || isFinishing || !dialog.isShowing) return@Runnable
                running = searchWorker.submit {
                    val matches = runCatching { store.searchMessages(query) }.getOrNull()
                    val titles = if (matches.isNullOrEmpty()) emptyMap() else {
                        runCatching { store.chats().associate { it.id to it.title } }.getOrDefault(emptyMap())
                    }
                    onUi {
                        if (!dialog.isShowing || request != generation) return@onUi
                        results.removeAllViews()
                        if (matches.isNullOrEmpty()) {
                            results.addView(ui.label(
                                if (matches == null) "Could not search messages." else "No matching messages or files",
                                14, ui.muted,
                            ))
                            return@onUi
                        }
                        for (row in matches) {
                            val label = ui.label(previewOf(row), 15, ui.ink, bold = true).apply {
                                maxLines = 2
                                ellipsize = TextUtils.TruncateAt.END
                            }
                            val target = titles[row.item.chatId] ?: "Chat"
                            val card = ui.column().apply {
                                setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(10))
                                background = ui.tapShape(ui.surface, 10)
                                addView(label)
                                addView(ui.label(target, 12, ui.muted))
                                isFocusable = true
                                setOnClickListener {
                                    dialog.dismiss()
                                    when {
                                        row.item.kind == "text" && canRead(row) -> showFullMessage(row)
                                        row.downloadUri != null -> openFile(row)
                                        else -> activateChat(row.item.chatId)
                                    }
                                }
                            }
                            results.addView(card)
                            results.addView(ui.rule())
                        }
                    }
                }
            }.also { mainHandler.postDelayed(it, 250) }
        }
        dialog.setOnDismissListener { cancelSearch() }
        dialog.show()
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            if (store.peer() != null && !addingComputer) {
                if (page == Page.CHAT || page == Page.RECEIVED) {
                    if (messages?.visibleItems().orEmpty().isNotEmpty()) {
                        menu.add(0, MENU_SELECT, 0, "Select messages")
                    }
                }
                if (page == Page.CHAT) {
                    menu.add(0, MENU_RENAME, 1, "Rename chat")
                    if (store.chats().size > 1) menu.add(0, MENU_DELETE_CHAT, 2, "Delete chat")
                    menu.add(0, MENU_MEDIA, 3, "Shared Media")
                    menu.add(0, MENU_SEND_FOLDER, 3, "Send folder").isEnabled = !stagingFiles
                    menu.add(0, MENU_RECEIVED, 4, "Received files")
                }
                menu.add(0, MENU_NOTIFICATIONS, 4, "Notifications & sounds")
                menu.add(0, MENU_SEARCH, 4, "Search messages")
                menu.add(0, MENU_SAVE_LOCATION, 5, "Save location")
                menu.add(0, MENU_PAUSE, 6, if (store.backgroundEnabled()) "Pause connection" else "Resume connection")
                menu.add(0, MENU_RECONNECT, 6, "Connect by address").isEnabled = !reconnecting
                menu.add(0, MENU_FORGET, 7, "Forget this computer")
                menu.add(0, MENU_COMPUTERS, 7, "Paired computers").isEnabled = canSwitchDesktop()
            } else if (addingComputer) {
                menu.add(0, MENU_CANCEL_PAIR, 7, "Cancel pairing").isEnabled = !pairing
            }
            val hotspot = if (OfflineHotspotService.isActive()) "Offline hotspot · on" else "Offline hotspot"
            menu.add(0, MENU_OFFLINE_HOTSPOT, 8, hotspot)
            menu.add(0, MENU_CHECK_UPDATES, 9, "Check for updates").isEnabled = !checkingForUpdates
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_SELECT -> enterSelection()
                    MENU_RENAME -> currentChat()?.let(::renameChat)
                    MENU_DELETE_CHAT -> currentChat()?.let(::confirmDeleteChat)
                    MENU_MEDIA -> switchPage(Page.MEDIA)
                    MENU_SEND_FOLDER -> pickFolder()
                    MENU_RECEIVED -> switchPage(Page.RECEIVED)
                    MENU_SEARCH -> showMessageSearch()
                    MENU_NOTIFICATIONS -> showNotificationSettings()
                    MENU_CHECK_UPDATES -> checkForUpdates()
                    MENU_SAVE_LOCATION -> showSaveLocationSettings()
                    MENU_RECONNECT -> showReconnectByAddress()
                    MENU_OFFLINE_HOTSPOT -> showOfflineHotspot()
                    MENU_PAUSE -> {
                        if (store.backgroundEnabled()) TransferService.pause(this@MainActivity)
                        else enableBackground()
                        refresh()
                    }
                    MENU_FORGET -> forgetComputer()
                    MENU_COMPUTERS -> showPairedComputers()
                    MENU_CANCEL_PAIR -> {
                        addingComputer = false
                        refresh()
                    }
                }
                true
            }
            show()
        }
    }

    private fun showReconnectByAddress() {
        val peer = store.peer() ?: return
        if (reconnecting) return
        val body = ui.column().apply { setPadding(ui.dp(24), ui.dp(12), ui.dp(24), 0) }
        body.addView(ui.label("Enter the address shown in Portal Drop on your computer.", 14, ui.muted))
        val input = ui.entry("Computer address", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).apply {
            setSingleLine(true)
            setSelectAllOnFocus(true)
            filters = arrayOf(InputFilter.LengthFilter(64))
            setText(if (peer.port == PortalClient.DEFAULT_PORT) peer.host else "${peer.host}:${peer.port}")
        }
        body.addWithGap(input, 12)
        val error = ui.label("", 13, ui.muted).apply { visibility = View.GONE }
        body.addWithGap(error, 8)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Connect by address")
            .setView(body)
            .setNegativeButton("Close", null)
            .setPositiveButton("Connect", null)
            .show()
        val connect = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        connect.setOnClickListener {
            val address = input.text.toString().trim()
            if (reconnecting) return@setOnClickListener
            if (address.isEmpty()) {
                error.text = "Enter your computer’s local IP address."
                error.visibility = View.VISIBLE
                return@setOnClickListener
            }
            reconnecting = true
            connect.isEnabled = false
            connect.text = "Connecting…"
            error.visibility = View.GONE
            worker.execute {
                val result = runCatching { PortalClient.reconnect(applicationContext, peer, address) }
                onUi {
                    reconnecting = false
                    if (result.isSuccess) {
                        dialog.dismiss()
                        if (store.peer()?.fingerprint == peer.fingerprint) enableBackground()
                    } else {
                        val failure = result.exceptionOrNull()
                        val message = when (failure) {
                            is IOException, is IllegalArgumentException -> failure.message
                            else -> null
                        } ?: "Portal Drop could not verify the computer at that address."
                        if (dialog.isShowing) {
                            connect.text = "Connect"
                            connect.isEnabled = true
                            error.text = message
                            error.visibility = View.VISIBLE
                        } else notifyUser(message)
                    }
                }
            }
        }
    }

    private fun showOfflineHotspot() {
        if (hotspotDialog?.isShowing == true) return
        val dialog = AlertDialog.Builder(this)
            .setTitle("Offline hotspot")
            .setMessage("")
            .setNegativeButton("Close", null)
            .setPositiveButton("Start hotspot", null)
            .show()
        hotspotDialog = dialog
        dialog.setOnDismissListener { hotspotDialog = null }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            when (OfflineHotspotService.state) {
                is OfflineHotspotService.State.Active -> OfflineHotspotService.stop(this)
                OfflineHotspotService.State.Starting -> Unit
                else -> {
                    if (hotspotPermissionGranted()) startOfflineHotspot()
                    else hotspotPermissions.launch(if (Build.VERSION.SDK_INT >= 33) {
                        arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
                    } else {
                        arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
                    })
                }
            }
        }
        renderHotspotDialog()
    }

    private fun renderHotspotDialog() {
        val dialog = hotspotDialog ?: return
        val state = OfflineHotspotService.state
        dialog.setMessage(when (state) {
            OfflineHotspotService.State.Off -> "Create a local Wi-Fi network your computer can join. File transfers work with mobile data off."
            OfflineHotspotService.State.Starting -> "Starting the hotspot…"
            is OfflineHotspotService.State.Active ->
                "Join this Wi-Fi network from your computer.\n\nWi-Fi name\n${state.ssid}\n\nPassword\n${state.passphrase}"
            is OfflineHotspotService.State.Failed -> state.message
        })
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            isEnabled = state !is OfflineHotspotService.State.Starting
            text = if (state is OfflineHotspotService.State.Active) "Stop hotspot" else "Start hotspot"
        }
    }

    private fun hotspotPermissionGranted(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun startOfflineHotspot() {
        if (Build.VERSION.SDK_INT < 33 && getSystemService(LocationManager::class.java)?.isLocationEnabled != true) {
            notifyUser("Turn on Location in Android Settings, then try again.")
            return
        }
        OfflineHotspotService.start(this)
        renderHotspotDialog()
    }

    private fun checkForUpdates() {
        if (checkingForUpdates) return
        checkingForUpdates = true
        notifyUser("Checking for updates…")
        updateWorker.execute {
            val result = runCatching {
                val current = if (Build.VERSION.SDK_INT >= 33) {
                    packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(packageName, 0).versionName
                }
                AppUpdates.newerThan(current.orEmpty())
            }
            onUi {
                checkingForUpdates = false
                if (isFinishing) return@onUi
                result.onSuccess { release ->
                    val dialog = AlertDialog.Builder(this).setNegativeButton("Close", null)
                    if (release == null) {
                        dialog.setTitle("No update available")
                            .setMessage("Your app is up to date.")
                    } else {
                        dialog.setTitle("Update available")
                            .setMessage("Portal Drop ${release.tag} is available on GitHub.")
                            .setPositiveButton("Open release") { _, _ -> openSharedLink(release.page) }
                    }
                    dialog.show()
                }.onFailure {
                    AlertDialog.Builder(this)
                        .setTitle("Could not check for updates")
                        .setMessage("Check your internet connection and try again.")
                        .setPositiveButton("Close", null)
                        .show()
                }
            }
        }
    }

    private fun previewSound(resId: Int) {
        try {
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
        } catch (_: Exception) {
            notifyUser("Could not play sound preview.")
        }
    }

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun showNotificationSettings() {
        var cur = store.notificationSettings()
        val content = ui.column().apply {
            setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(8))
        }
        content.addView(ui.label(
            "Turn notifications and sounds on or off independently for each event.",
            13, ui.muted,
        ))

        val thumbColors = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(ui.accent, ui.muted),
        )
        val trackColors = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(ui.mint, ui.line),
        )

        fun switchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
            val row = ui.row().apply {
                setPadding(0, ui.dp(6), 0, ui.dp(6))
            }
            row.addView(ui.label(label, 14, ui.ink), LinearLayout.LayoutParams(0, WRAP, 1f))
            val toggle = Switch(this).apply {
                isChecked = checked
                thumbTintList = thumbColors
                trackTintList = trackColors
                setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
            }
            row.addView(toggle, LinearLayout.LayoutParams(WRAP, WRAP))
            return row
        }

        fun section(
            title: String,
            subtitle: String,
            soundRes: Int,
            notifyChecked: Boolean,
            soundChecked: Boolean,
            onNotify: (Boolean) -> Unit,
            onSound: (Boolean) -> Unit,
        ) {
            val card = ui.column().apply {
                background = ui.shape(ui.surface, 14, ui.line)
                setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(10))
            }
            val head = ui.row()
            val titles = ui.column().apply {
                addView(ui.label(title, 15, ui.ink, bold = true))
                addView(ui.label(subtitle, 12, ui.muted))
            }
            head.addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))
            val testBtn = ui.action("Test") { previewSound(soundRes) }.apply {
                textSize = 12f
                setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4))
                background = ui.tapShape(ui.mint, 14, ui.line)
            }
            head.addView(testBtn, LinearLayout.LayoutParams(WRAP, WRAP))
            card.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
            card.addWithGap(switchRow("Show notification", notifyChecked, onNotify), 6)
            card.addView(switchRow("Play sound", soundChecked, onSound), LinearLayout.LayoutParams(MATCH, WRAP))
            content.addWithGap(card, 12)
        }

        section(
            "Incoming messages",
            "New text messages and replies",
            R.raw.portal_message,
            cur.messageNotify,
            cur.messageSound,
            onNotify = { cur = cur.copy(messageNotify = it); store.setNotificationSettings(cur) },
            onSound = { cur = cur.copy(messageSound = it); store.setNotificationSettings(cur) },
        )
        section(
            "Received files",
            "Completed file downloads",
            R.raw.portal_file,
            cur.fileNotify,
            cur.fileSound,
            onNotify = { cur = cur.copy(fileNotify = it); store.setNotificationSettings(cur) },
            onSound = { cur = cur.copy(fileSound = it); store.setNotificationSettings(cur) },
        )
        section(
            "Device connected",
            "When your computer connects on Wi-Fi",
            R.raw.portal_connected,
            cur.connectedNotify,
            cur.connectedSound,
            onNotify = { cur = cur.copy(connectedNotify = it); store.setNotificationSettings(cur) },
            onSound = { cur = cur.copy(connectedSound = it); store.setNotificationSettings(cur) },
        )

        val scroll = ScrollView(this).apply {
            addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        AlertDialog.Builder(this)
            .setTitle("Notifications & sounds")
            .setView(scroll)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun canSwitchDesktop(): Boolean = !stagingFiles && !queueingText && attachmentReview == null && !pairing

    private fun showPairedComputers() {
        val peers = store.rememberedPeers()
        val active = store.peer()?.fingerprint
        val labels = peers.map { peer ->
            val name = peer.name ?: peer.platformLabel()
            if (peer.fingerprint == active) "$name · Active" else name
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Paired computers")
            .setItems(labels) { _, index ->
                if (canSwitchDesktop() && TransferService.selectDesktop(this, peers[index].fingerprint)) {
                    refresh()
                }
            }
            .setPositiveButton("Add a computer") { _, _ ->
                if (canSwitchDesktop()) {
                    addingComputer = true
                    refresh()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun forgetComputer() {
        val platform = store.peer()?.platformLabel() ?: "your computer"
        AlertDialog.Builder(this)
            .setTitle("Forget this computer?")
            .setMessage("To pair again, unpair on $platform (or run portal-drop unpair) and create a new invite.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Forget") { _, _ ->
                if (!canSwitchDesktop()) return@setPositiveButton
                val enabled = store.backgroundEnabled()
                TransferService.pause(this)
                store.forgetPeer()
                if (enabled && store.peer() != null) enableBackground() else refresh()
            }.show()
    }

    private fun replyTo(row: ChatItem) {
        if (page != Page.CHAT || row.item.chatId != chatId) activateChat(row.item.chatId)
        saveDraft()
        drafts[chatId] = Draft(composer?.text.toString(), row)
        fillReplyArea()
        composer?.let {
            it.requestFocus()
            getSystemService(InputMethodManager::class.java).showSoftInput(it, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun showMessageMenu(anchor: View, message: ChatItem) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MESSAGE_REPLY, 0, "Reply")
            menu.add(0, MESSAGE_SELECT, 1, "Select messages")
            if (message.item.kind == "text" && canRead(message)) {
                menu.add(0, MESSAGE_COPY, 2, "Copy exact text")
                if (message.item.textSize != null) menu.add(0, MESSAGE_FULL, 3, "View full message")
            }
            if (message.item.kind == "file" && message.localFileUri() != null) {
                menu.add(0, MESSAGE_OPEN, 3, "Open with app")
                if (message.incoming) menu.add(0, MESSAGE_OPEN_FOLDER, 4, "Open in folder")
            }
            for (action in message.availableTransferActions()) {
                menu.add(0, MESSAGE_TRANSFER_BASE + action.ordinal, 5, "${action.label} transfer")
            }
            menu.add(0, MESSAGE_DELETE, 6, "Delete here")
            setOnMenuItemClickListener { option ->
                when (option.itemId) {
                    MESSAGE_REPLY -> replyTo(message)
                    MESSAGE_SELECT -> enterSelection(message)
                    MESSAGE_COPY -> copyText(message)
                    MESSAGE_FULL -> showFullMessage(message)
                    MESSAGE_OPEN -> openFile(message, forceChooser = true)
                    MESSAGE_OPEN_FOLDER -> openFileFolder(message)
                    MESSAGE_DELETE -> deleteMessage(message)
                    else -> TransferAction.entries.getOrNull(option.itemId - MESSAGE_TRANSFER_BASE)?.let {
                        controlTransfer(message, it)
                    }
                }
                true
            }
            show()
        }
    }

    private fun controlTransfer(message: ChatItem, action: TransferAction) {
        if (action == TransferAction.CANCEL) {
            AlertDialog.Builder(this)
                .setTitle("Cancel transfer?")
                .setMessage(message.item.name.orEmpty())
                .setNegativeButton("Keep transfer", null)
                .setPositiveButton("Cancel transfer") { _, _ -> applyTransferAction(message, action) }
                .show()
        } else applyTransferAction(message, action)
    }

    private fun applyTransferAction(message: ChatItem, action: TransferAction) {
        worker.execute {
            val changed = runCatching { TransferService.control(applicationContext, message, action) }.getOrDefault(false)
            onUi {
                refresh()
                if (!changed) notifyUser("This transfer is no longer available for that action.")
            }
        }
    }

    private fun showSaveLocationSettings() {
        val content = ui.column().apply {
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(8))
        }
        content.addView(ui.label(
            "Choose where incoming files are saved. You can select any folder on internal storage or your microSD card.",
            13, ui.muted,
        ))
        val card = ui.column().apply {
            background = ui.shape(ui.surface, 14, ui.line)
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
        }
        card.addView(ui.label("Current save folder", 12, ui.muted, bold = true))
        val pathView = ui.label(store.saveLocationLabel(), 14, ui.ink, bold = true).apply {
            setPadding(0, ui.dp(4), 0, ui.dp(10))
        }
        card.addView(pathView)

        val btnRow = ui.row()
        val chooseBtn = ui.action("Choose folder…") {
            try {
                pickSaveFolder.launch(store.saveTreeUri())
            } catch (_: ActivityNotFoundException) {
                notifyUser("No folder picker is available on this device.")
            }
        }.apply {
            textSize = 13f
            setTextColor(ui.onAccent)
            setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
            background = ui.tapShape(ui.accent, 18)
        }
        val openBtn = ui.action("Open folder") {
            openSaveFolder()
        }.apply {
            textSize = 13f
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
            background = ui.tapShape(ui.mint, 18, ui.line)
        }
        btnRow.addView(chooseBtn, LinearLayout.LayoutParams(WRAP, WRAP))
        btnRow.addView(openBtn, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ui.dp(8) })
        card.addView(btnRow, LinearLayout.LayoutParams(MATCH, WRAP))

        val resetBtn = ui.action("Reset to Downloads") {
            store.setSaveTreeUri(null)
            pathView.text = store.saveLocationLabel()
            saveLocationResetBtn?.visibility = View.GONE
            notifyUser("Reset to Downloads/Portal Drop")
        }.apply {
            textSize = 13f
            setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6))
            visibility = if (store.saveTreeUri() != null) View.VISIBLE else View.GONE
        }
        card.addWithGap(resetBtn, 8)
        content.addWithGap(card, 12)

        saveLocationLabelView = pathView
        saveLocationResetBtn = resetBtn
        AlertDialog.Builder(this)
            .setTitle("Save location")
            .setView(content)
            .setPositiveButton("Done", null)
            .setOnDismissListener {
                saveLocationLabelView = null
                saveLocationResetBtn = null
            }
            .show()
    }

    private fun openSaveFolder() {
        val tree = store.saveTreeUri()
        if (tree != null) {
            val docId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
            val folderUri = if (docId != null) DocumentsContract.buildDocumentUriUsingTree(tree, docId) else tree
            if (launchFolderIntent(folderUri) || launchFolderIntent(tree)) return
        } else {
            val docUri = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:Download/Portal Drop",
            )
            if (launchFolderIntent(docUri)) return
        }
        if (!openSystemDownloads()) {
            notifyUser("Files are saved in ${store.saveLocationLabel()}.")
        }
    }

    private fun openFileFolder(message: ChatItem) {
        val uri = message.downloadUri ?: return
        var folderLabel = store.saveLocationLabel()
        val folderUri = if (uri.authority == MediaStore.AUTHORITY) {
            val rel = runCatching {
                contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull()?.trim('/')?.takeIf { it.isNotEmpty() } ?: "Download/Portal Drop"
            folderLabel = rel
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$rel")
        } else {
            val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            val parentDocId = docId?.let { id ->
                val slash = id.lastIndexOf('/')
                if (slash > 0) id.substring(0, slash) else id.substringBefore(':', "") + ":"
            }
            val tree = store.saveTreeUri()
            when {
                tree != null && parentDocId != null ->
                    DocumentsContract.buildDocumentUriUsingTree(tree, parentDocId)
                uri.authority != null && parentDocId != null ->
                    DocumentsContract.buildDocumentUri(uri.authority!!, parentDocId)
                tree != null -> tree
                else -> null
            }
        }
        if (folderUri != null && launchFolderIntent(folderUri)) return
        val fallbackTree = store.saveTreeUri()
        if (fallbackTree != null && launchFolderIntent(fallbackTree)) return
        if (!openSystemDownloads()) {
            notifyUser("Saved in $folderLabel")
        }
    }

    private fun launchFolderIntent(folderUri: Uri): Boolean {
        for (mime in listOf(DocumentsContract.Document.MIME_TYPE_DIR, "resource/folder")) {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(folderUri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (tryStartActivity(intent)) return true
        }
        val plain = Intent(Intent.ACTION_VIEW, folderUri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return tryStartActivity(plain)
    }

    private fun openSystemDownloads(): Boolean =
        tryStartActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun tryStartActivity(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun deleteMessage(message: ChatItem) {
        val hasReceivedFile = message.item.kind == "file" && message.incoming && message.downloadUri != null
        val body = ui.column().apply {
            setPadding(ui.dp(22), ui.dp(10), ui.dp(22), ui.dp(4))
            addView(ui.label(
                "This removes the item from this device’s chat history." +
                    if (hasReceivedFile) " The saved file stays in Downloads unless checked below." else "",
                14, ui.muted,
            ))
        }
        val checkBox = if (hasReceivedFile) {
            CheckBox(this).apply {
                text = "Also delete received file from Downloads"
                setTextColor(ui.ink)
                textSize = 14f
                buttonTintList = ColorStateList.valueOf(ui.accent)
            }.also { body.addWithGap(it, 12) }
        } else null
        AlertDialog.Builder(this)
            .setTitle("Delete this item here?")
            .setView(body)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete here") { _, _ ->
                store.deleteItem(
                    message.item.chatId,
                    message.item.id,
                    message.incoming,
                    deleteDownloadedFile = checkBox?.isChecked == true,
                )
                refresh()
            }.show()
    }

    private fun copyText(message: ChatItem) {
        worker.execute {
            val clip = try {
                if ((message.item.textSize ?: 0) > DIRECT_CLIPBOARD_BYTES) {
                    store.sharedTextUri(message)?.let { ClipData.newUri(contentResolver, "Portal Drop message", it) }
                } else {
                    store.readText(message)?.let { ClipData.newPlainText("Portal Drop message", it) }
                }
            } catch (_: Exception) {
                null
            }
            onUi {
                if (clip == null) notifyUser("Could not copy this message.")
                else try {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                    if (Build.VERSION.SDK_INT < 33) notifyUser("Copied")
                } catch (_: RuntimeException) {
                    notifyUser("Could not copy this message.")
                }
            }
        }
    }

    private fun showFullMessage(message: ChatItem) {
        val large = (message.item.textSize ?: 0) > FULL_MESSAGE_VIEW_BYTES
        worker.execute {
            val text = try {
                if (large) store.previewText(message) else store.readText(message)
            } catch (_: Exception) {
                null
            }
            onUi {
                if (text == null) {
                    notifyUser("This message is not available yet.")
                    return@onUi
                }
                val body = ui.label("", 15, ui.ink).apply {
                    setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12))
                    this.text = ui.markup.render(text)
                    setTextIsSelectable(true)
                }
                val scroll = ScrollView(this).apply {
                    addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
                }
                AlertDialog.Builder(this)
                    .setTitle(if (large) "Message preview" else "Message")
                    .setView(scroll)
                    .setNegativeButton("Close", null)
                    .setNeutralButton("Copy exact text") { _, _ -> copyText(message) }
                    .apply {
                        if (large) setPositiveButton("Share text file") { _, _ -> shareText(message) }
                    }
                    .show()
            }
        }
    }

    private fun shareText(message: ChatItem) {
        worker.execute {
            val uri = try { store.sharedTextUri(message) } catch (_: Exception) { null }
            onUi {
                if (uri != null) {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        clipData = ClipData.newUri(contentResolver, "Portal Drop message", uri)
                    }
                    startActivity(Intent.createChooser(share, null))
                } else notifyUser("Could not share this message.")
            }
        }
    }

    private fun clearHistory() {
        AlertDialog.Builder(this)
            .setTitle("Clear received file history?")
            .setMessage("This clears the list. Find saved files in Downloads.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear history") { _, _ ->
                store.clearReceivedHistory()
                refresh()
            }
            .show()
    }

    private fun openSharedFile(row: ChatItem) {
        if (row.localFileUri() == null) {
            notifyUser("The original file is unavailable on this phone.")
        } else openFile(row)
    }

    private fun openSharedLink(uri: Uri) {
        if ((uri.scheme != "https" && uri.scheme != "http") || uri.host.isNullOrBlank()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            notifyUser("No web browser is available.")
        } catch (_: SecurityException) {
            notifyUser("This link could not be opened.")
        }
    }

    private fun openFile(row: ChatItem, forceChooser: Boolean = false) {
        val uri = row.localFileUri() ?: return
        val providerType = try { contentResolver.getType(uri) } catch (_: RuntimeException) { null }
        val mime = resolveMimeType(row.item.name, providerType)

        if (mime == "application/vnd.android.package-archive") {
            if (!packageManager.canRequestPackageInstalls()) {
                AlertDialog.Builder(this)
                    .setTitle("Allow app installs?")
                    .setMessage("Enable “Install unknown apps” for Portal Drop so you can install received APK files directly.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Open settings") { _, _ ->
                        try {
                            startActivity(Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:$packageName"),
                            ))
                        } catch (_: ActivityNotFoundException) {
                            notifyUser("Enable Install unknown apps for Portal Drop in Android Settings.")
                        }
                    }
                    .show()
                return
            }
            launchFileIntent(uri, mime, forceChooser = false)
            return
        }

        if (!launchFileIntent(uri, mime, forceChooser)) {
            if (!launchFileIntent(uri, "*/*", forceChooser = true)) {
                notifyUser("No installed app can open this file. Find it in Downloads/Portal Drop.")
            }
        }
    }

    private fun launchFileIntent(uri: Uri, mime: String, forceChooser: Boolean): Boolean {
        val viewIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            val target = if (forceChooser) Intent.createChooser(viewIntent, "Open with") else viewIntent
            startActivity(target)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            notifyUser("This file is no longer accessible.")
            true
        }
    }

    private fun hideKeyboard() {
        currentFocus?.let {
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0)
            it.clearFocus()
        }
    }

    private fun notifyUser(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun onUi(update: () -> Unit) = runOnUiThread {
        if (!isDestroyed) update()
    }

    private companion object {
        val TEMP_TIMERS = linkedMapOf(300L to "5 minutes", TEMP_MESSAGE_TTL_SEC to "10 minutes", 3600L to "1 hour", 86400L to "24 hours")
        const val MENU_SELECT = 1
        const val MENU_RENAME = 2
        const val MENU_DELETE_CHAT = 3
        const val MENU_RECEIVED = 4
        const val MENU_PAUSE = 5
        const val MENU_FORGET = 6
        const val MENU_NOTIFICATIONS = 7
        const val MENU_SAVE_LOCATION = 8
        const val MENU_SEARCH = 9
        const val MENU_MEDIA = 10
        const val MENU_CHECK_UPDATES = 11
        const val MENU_SEND_FOLDER = 12
        const val MENU_COMPUTERS = 13
        const val MENU_CANCEL_PAIR = 14
        const val MENU_RECONNECT = 15
        const val MENU_OFFLINE_HOTSPOT = 16
        const val CHAT_MENU_RENAME = 1
        const val CHAT_MENU_DELETE = 2
        const val MESSAGE_REPLY = 1
        const val MESSAGE_SELECT = 2
        const val MESSAGE_COPY = 3
        const val MESSAGE_FULL = 4
        const val MESSAGE_OPEN = 5
        const val MESSAGE_OPEN_FOLDER = 6
        const val MESSAGE_DELETE = 7
        const val MESSAGE_TRANSFER_BASE = 20
        const val DIRECT_CLIPBOARD_BYTES = 128 * 1024
        const val FULL_MESSAGE_VIEW_BYTES = 256 * 1024
    }
}
