package app.portaldrop

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.PersistableBundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

internal class OfflineSharingSheet(
    private val context: Context,
    private val ui: PortalViews,
    private val onStart: (String?) -> Unit,
    private val onStop: () -> Unit,
    private val onSystemHotspot: () -> Unit,
    private val onJoinNetwork: () -> Unit,
    private val onResume: () -> Unit,
    private val onConnectByAddress: () -> Unit,
) {
    private val sheet = PortalSheet(context, ui, "Offline sharing")
    val dialog get() = sheet.dialog
    private val supportsPassphrase = OfflineHotspotService.supportsCustomPassphrase()
    private val rings = HotspotRings(context, ui.accent)
    private val hotspotButton = ui.iconAction(
        R.drawable.ic_portal_hotspot, "Start hotspot", fill = ui.accent, color = ui.onAccent,
    ) { start() }
    private val hotspotIcon = hotspotButton.getChildAt(0) as ImageView
    private val hotspotTitle = ui.label("", 22, ui.ink, bold = true).apply {
        gravity = Gravity.CENTER
        isAccessibilityHeading = true
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val hotspotDescription = ui.label("", 14, ui.muted).apply {
        gravity = Gravity.CENTER
        setPadding(ui.dp(10), 0, ui.dp(10), 0)
    }
    private val password = ui.entry(
        "Wi-Fi password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
    ).apply {
        filters = arrayOf(InputFilter.LengthFilter(63))
        setSingleLine(true)
        visibility = View.GONE
    }
    private var usePassword = false
    private var joiningDesktop = false
    private val options = wifiOptions()
    private val session = ui.column()
    private val join = ui.action("Join a computer hotspot") {
        setJoiningDesktop(true)
        onJoinNetwork()
    }
    private data class Presentation(
        val state: OfflineHotspotService.State,
        val peerName: String?,
        val verified: Boolean,
        val backgroundEnabled: Boolean,
        val joiningDesktop: Boolean,
    )
    private var last: Presentation? = null

    init {
        sheet.body.apply {
            addView(FrameLayout(context).apply {
                addView(rings, FrameLayout.LayoutParams(ui.dp(174), ui.dp(174), Gravity.CENTER))
                addView(hotspotButton, FrameLayout.LayoutParams(ui.dp(88), ui.dp(88), Gravity.CENTER))
                hotspotIcon.layoutParams = FrameLayout.LayoutParams(ui.dp(36), ui.dp(36), Gravity.CENTER)
            }, LinearLayout.LayoutParams(MATCH, ui.dp(172)))
            addView(hotspotTitle)
            addWithGap(hotspotDescription, 3)
            addWithGap(options, 17)
            addWithGap(session, 12)
            addWithGap(join, 8)
        }
    }

    fun show() = sheet.show()

    fun render(
        state: OfflineHotspotService.State,
        peerName: String?,
        verified: Boolean,
        backgroundEnabled: Boolean,
    ) {
        val current = Presentation(state, peerName, verified, backgroundEnabled, joiningDesktop)
        if (current == last) return
        last = current
        val active = state is OfflineHotspotService.State.Active || state == OfflineHotspotService.State.SystemHotspot
        val starting = state == OfflineHotspotService.State.Starting
        val joining = joiningDesktop && !active && !starting
        val participating = active || joining
        val connected = participating && verified
        rings.scanning = starting || (participating && !connected)
        hotspotButton.apply {
            isEnabled = !participating && !starting
            isFocusable = isEnabled
            isClickable = isEnabled
            importantForAccessibility = if (isEnabled) {
                View.IMPORTANT_FOR_ACCESSIBILITY_YES
            } else {
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
        }
        hotspotIcon.setImageResource(if (connected) R.drawable.ic_chat_check else R.drawable.ic_portal_hotspot)
        options.visibility = if (participating || starting) View.GONE else View.VISIBLE
        join.visibility = if (participating || starting) View.GONE else View.VISIBLE
        hotspotTitle.text = when {
            connected -> "Paired computer connected"
            joining -> "Joining computer hotspot"
            active && peerName != null -> "Waiting for your computer"
            active -> "Hotspot ready"
            starting -> "Starting hotspot"
            state is OfflineHotspotService.State.Failed -> "Hotspot unavailable"
            else -> "Start hotspot"
        }
        hotspotDescription.setTextColor(
            if (state is OfflineHotspotService.State.Failed && !joining) ui.errorColor else ui.muted,
        )
        hotspotDescription.text = when {
            connected -> peerName.orEmpty()
            joining && peerName != null -> "Join $peerName’s hotspot from Wi-Fi settings."
            joining -> "Join your computer’s hotspot, then open its pairing invite."
            state == OfflineHotspotService.State.SystemHotspot -> "Turn on your phone’s hotspot in Android settings, then join from your computer."
            active && peerName != null -> "Join the Wi-Fi network below from $peerName."
            active -> "Join the Wi-Fi network, then scan your computer’s pairing invite."
            starting -> "Getting the Wi-Fi network ready…"
            state is OfflineHotspotService.State.Failed -> state.message
            else -> "Share files and messages over a local Wi-Fi network."
        }
        session.removeAllViews()
        if (state is OfflineHotspotService.State.Active) session.addView(networkCard(state))
        if (participating && !connected && peerName != null) {
            session.addWithGap(if (backgroundEnabled) {
                ui.action("Connect by address") { onConnectByAddress() }
            } else {
                ui.action("Resume connection") { onResume() }
            }, 4)
        }
        if (state == OfflineHotspotService.State.SystemHotspot) {
            session.addWithGap(ui.action("Open Android settings") { onSystemHotspot() }, 4)
        }
        if (joining) {
            if (!connected) {
                session.addWithGap(ui.action("Open Wi-Fi settings") { onJoinNetwork() }, 4)
            }
            session.addWithGap(ui.action("Back to hotspot options") { setJoiningDesktop(false) }, 3)
        }
        if (active) {
            val label = if (state == OfflineHotspotService.State.SystemHotspot) "End hotspot setup" else "Stop hotspot"
            session.addWithGap(ui.action(label) { onStop() }.apply { setTextColor(ui.errorColor) }, 3)
        }
        if (dialog.isShowing) sheet.fit()
    }

    private fun setJoiningDesktop(joining: Boolean) {
        joiningDesktop = joining
        last?.let { render(it.state, it.peerName, it.verified, it.backgroundEnabled) }
    }

    private fun wifiOptions(): View = ui.column().apply {
        setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(8))
        background = ui.shape(ui.surface, 16, ui.line)
        addView(ui.label("Network access", 15, ui.ink, bold = true).apply { isAccessibilityHeading = true })
        if (supportsPassphrase) {
            addView(RadioGroup(context).apply {
                orientation = RadioGroup.VERTICAL
                val open = RadioButton(context).apply {
                    id = View.generateViewId()
                    text = "Open"
                }
                val secured = RadioButton(context).apply {
                    id = View.generateViewId()
                    text = "Use a Wi-Fi password"
                }
                addView(open)
                addView(secured)
                check(open.id)
                setOnCheckedChangeListener { _, checked ->
                    usePassword = checked == secured.id
                    password.visibility = if (usePassword) View.VISIBLE else View.GONE
                    if (dialog.isShowing) sheet.fit()
                }
            })
            addView(password)
        } else {
            addWithGap(ui.label(
                "Android chooses the Wi-Fi security on this phone. You can join with a QR code.",
                14, ui.ink,
            ), 4)
        }
        addWithGap(ui.label("Only paired Portal Drop devices can exchange files and messages.", 12, ui.muted), 6)
        if (!supportsPassphrase) {
            addWithGap(ui.action("Use Android hotspot settings") { onSystemHotspot() }, 4)
        }
    }

    private fun networkCard(state: OfflineHotspotService.State.Active): View = ui.column().apply {
        setPadding(ui.dp(14), ui.dp(11), ui.dp(14), ui.dp(8))
        background = ui.shape(ui.surface, 16, ui.line)
        addView(ui.label("Wi-Fi network", 12, ui.muted))
        addWithGap(ui.label(state.ssid, 17, ui.ink, bold = true).apply { setTextIsSelectable(true) }, 2)
        val passphrase = state.passphrase
        addWithGap(ui.label(if (passphrase == null) "Open Wi-Fi" else "Password", 12, ui.muted), 7)
        if (passphrase != null) {
            addWithGap(ui.label(passphrase, 15, ui.ink).apply {
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
            }, 2)
        }
        addWithGap(ui.label("Only paired Portal Drop devices can exchange files and messages.", 12, ui.muted), 7)
        addWithGap(ui.row().apply {
            addView(ui.action("Wi-Fi QR code") { showWifiQr(state) }, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (passphrase != null) {
                addView(ui.action("Copy password") { copyPassword(passphrase) },
                    LinearLayout.LayoutParams(0, WRAP, 1f))
            }
        }, 4)
    }

    private fun start() {
        val passphrase = password.text.toString().takeIf { usePassword }
        if (passphrase != null && (passphrase.length !in 8..63 || passphrase.any { it !in ' '..'~' })) {
            password.error = "Use 8–63 printable characters."
            password.requestFocus()
            return
        }
        onStart(passphrase)
    }

    private fun showWifiQr(state: OfflineHotspotService.State.Active) {
        fun escape(text: String) = buildString {
            text.forEach { letter ->
                if (letter in "\\;,\":") append('\\')
                append(letter)
            }
        }
        val key = state.passphrase
        val payload = if (key == null) {
            "WIFI:T:nopass;S:${escape(state.ssid)};;"
        } else {
            "WIFI:T:WPA;S:${escape(state.ssid)};P:${escape(key)};;"
        }
        val bitmap = runCatching {
            val size = 256
            val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
            val pixels = IntArray(size * size) { index ->
                if (matrix[index % size, index / size]) Color.BLACK else Color.WHITE
            }
            Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        }.getOrElse {
            Toast.makeText(context, "Could not display this Wi-Fi QR code.", Toast.LENGTH_SHORT).show()
            return
        }
        val image = ImageView(context).apply {
            setImageBitmap(bitmap)
            contentDescription = "QR code to join ${state.ssid}"
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        AlertDialog.Builder(context)
            .setTitle("Join Wi-Fi")
            .setView(FrameLayout(context).apply {
                setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12))
                addView(image, FrameLayout.LayoutParams(ui.dp(200), ui.dp(200), Gravity.CENTER))
            })
            .setPositiveButton("Done", null)
            .show()
    }

    private fun copyPassword(password: String) {
        val clip = ClipData.newPlainText("Hotspot password", password)
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        Toast.makeText(context, "Wi-Fi password copied.", Toast.LENGTH_SHORT).show()
    }
}

private class HotspotRings(context: Context, color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1700L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }
    var scanning = false
        set(value) {
            if (field == value) return
            field = value
            updateAnimation()
        }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    private fun updateAnimation() {
        if (scanning && isAttachedToWindow && windowVisibility == VISIBLE && ValueAnimator.areAnimatorsEnabled()) {
            if (!animator.isStarted) animator.start()
        } else {
            animator.cancel()
            phase = 0f
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val x = width / 2f
        val y = height / 2f
        val radius = minOf(x, y)
        paint.style = Paint.Style.FILL
        paint.alpha = 14
        canvas.drawCircle(x, y, radius * .91f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = resources.displayMetrics.density
        for (index in 0..2) {
            val progress = (phase + index / 3f) % 1f
            paint.alpha = if (scanning) ((1f - progress) * 92).toInt() else 32
            canvas.drawCircle(x, y, radius * (.55f + progress * .45f), paint)
        }
    }
}
