package app.portaldrop

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

internal const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
internal const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

internal class PortalViews(private val context: Context) {
    val canvasColor get() = context.getColor(R.color.portal_background)
    val surface get() = context.getColor(R.color.portal_surface)
    val ink get() = context.getColor(R.color.portal_text)
    val muted get() = context.getColor(R.color.portal_muted)
    val line get() = context.getColor(R.color.portal_border)
    val accent get() = context.getColor(R.color.portal_accent)
    val onAccent get() = context.getColor(R.color.portal_on_accent)
    val mint get() = context.getColor(R.color.portal_mint)
    val replyColor get() = context.getColor(R.color.portal_reply)
    val errorColor get() = context.getColor(R.color.portal_error)
    val accentText get() = context.getColor(R.color.portal_accent_text)
    val inputColor get() = context.getColor(R.color.portal_input)
    val receivedBubble get() = context.getColor(R.color.portal_bubble_in)
    val sentMeta get() = context.getColor(R.color.portal_meta_sent)
    val dayColor get() = context.getColor(R.color.portal_day_bg)
    val dayInk get() = context.getColor(R.color.portal_day_text)
    val selectedColor get() = context.getColor(R.color.portal_selected)
    val markup by lazy { MessageMarkup(accentText, replyColor) }

    fun avatar(id: String, title: String, size: Int) = FrameLayout(context).apply {
        val initials = title.trim().split(Regex("""\s+"""))
            .filter { word -> word.firstOrNull()?.isLetter() == true }
            .take(2).joinToString("") { it.take(1).uppercase() }
        val palette = AVATAR_COLORS[id.hashCode().toUInt().rem(AVATAR_COLORS.size.toUInt()).toInt()]
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, palette).apply {
            shape = GradientDrawable.OVAL
        }
        val inset = dp(size / 2)
        if (initials.isEmpty()) {
            addView(symbol(R.drawable.ic_chat_chat, -1), FrameLayout.LayoutParams(inset, inset, Gravity.CENTER))
        } else {
            addView(label(initials, if (size >= 48) 17 else 13, -1, bold = true),
                FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun row() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun rule() = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(1))
        setBackgroundColor(line)
    }

    fun label(text: String, size: Int, color: Int, bold: Boolean = false) =
        TextView(context).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    fun action(text: String, primary: Boolean = false, click: (View) -> Unit) =
        label(text, 14, if (primary) onAccent else accentText, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            minWidth = dp(48)
            setPadding(dp(12), 0, dp(12), 0)
            background = tapShape(if (primary) accent else android.graphics.Color.TRANSPARENT, 14)
            setOnClickListener(click)
            isFocusable = true
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(view: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(view, info)
                    info.className = "android.widget.Button"
                }
            }
        }

    fun entry(hint: String, type: Int) = EditText(context).apply {
        this.hint = hint
        inputType = type
        setTextColor(ink)
        setHintTextColor(muted)
        textSize = 16f
        minHeight = dp(48)
        setPadding(dp(13), dp(10), dp(13), dp(10))
        background = shape(inputColor, 14, line)
    }

    fun symbol(icon: Int, color: Int) = ImageView(context).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(color)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun iconAction(
        icon: Int, description: String, fill: Int = android.graphics.Color.TRANSPARENT,
        color: Int = accent, click: (View) -> Unit,
    ) = FrameLayout(context).apply {
        contentDescription = description
        isFocusable = true
        background = tapShape(fill, 24)
        addView(symbol(icon, color), FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER))
        setOnClickListener(click)
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(view: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(view, info)
                info.className = "android.widget.Button"
            }
        }
    }

    fun tapBubble(
        color: Int, incoming: Boolean, groupedEnd: Boolean, border: Int? = null, borderWidthDp: Int = 1,
    ): RippleDrawable {
        fun bubbleShape(fill: Int, strokeColor: Int?) = GradientDrawable().apply {
            setColor(fill)
            val large = dp(16).toFloat()
            val small = if (groupedEnd) dp(5).toFloat() else large
            cornerRadii = floatArrayOf(
                large, large, large, large,
                if (incoming) large else small, if (incoming) large else small,
                if (incoming) small else large, if (incoming) small else large,
            )
            strokeColor?.let { setStroke(dp(borderWidthDp), it) }
        }
        return RippleDrawable(
            ColorStateList.valueOf(0x20808f87),
            bubbleShape(color, border),
            bubbleShape(-1, null),
        )
    }

    fun shape(color: Int, radius: Int, border: Int? = null, borderWidthDp: Int = 1) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            border?.let { setStroke(dp(borderWidthDp), it) }
        }

    fun tapShape(color: Int, radius: Int, border: Int? = null) =
        RippleDrawable(
            ColorStateList.valueOf(0x29808f87),
            shape(color, radius, border),
            shape(0xffffffff.toInt(), radius),
        )

    fun dp(value: Int) = (context.resources.displayMetrics.density * value).roundToInt()

    private companion object {
        val AVATAR_COLORS = arrayOf(
            intArrayOf(0xFF2AA976.toInt(), 0xFF147046.toInt()),
            intArrayOf(0xFF388FBF.toInt(), 0xFF236699.toInt()),
            intArrayOf(0xFFCF814F.toInt(), 0xFFA44932.toInt()),
            intArrayOf(0xFF9272CF.toInt(), 0xFF6343AD.toInt()),
            intArrayOf(0xFFC86A96.toInt(), 0xFF9A3564.toInt()),
            intArrayOf(0xFF419C99.toInt(), 0xFF207275.toInt()),
            intArrayOf(0xFFB48A2D.toInt(), 0xFF795A16.toInt()),
        )
    }
}

internal fun LinearLayout.addWithGap(view: View, gap: Int) {
    val margin = (resources.displayMetrics.density * gap).roundToInt()
    addView(view, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = margin })
}
