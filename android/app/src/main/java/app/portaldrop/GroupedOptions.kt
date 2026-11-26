package app.portaldrop

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView

internal data class OptionSection(val heading: String, val rows: List<OptionRow>)

internal data class OptionRow(
    val title: String,
    val icon: Int,
    val detail: String? = null,
    val opensPage: Boolean = false,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val act: () -> Unit,
)

internal fun showGroupedOptions(
    context: Context,
    ui: PortalViews,
    title: String,
    sections: List<OptionSection>,
) {
    val sheet = PortalSheet(context, ui, title)
    sections.filter { it.rows.isNotEmpty() }.forEach { section ->
        sheet.body.addWithGap(ui.label(section.heading, 13, ui.muted, bold = true).apply {
            setPadding(ui.dp(4), 0, 0, 0)
            isAccessibilityHeading = true
        }, 12)
        sheet.body.addWithGap(ui.column().apply {
            background = ui.shape(ui.surface, 16, ui.line)
            clipToOutline = true
            section.rows.forEachIndexed { index, item ->
                if (index > 0) addView(ui.rule().apply {
                    (layoutParams as LinearLayout.LayoutParams).marginStart = ui.dp(52)
                })
                val color = if (item.destructive) ui.errorColor else ui.accentText
                addView(ui.row().apply {
                    minimumHeight = ui.dp(50)
                    setPadding(ui.dp(15), ui.dp(9), ui.dp(13), ui.dp(9))
                    background = ui.tapShape(Color.TRANSPARENT, 0)
                    addView(ui.symbol(item.icon, color), LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)))
                    addView(ui.column().apply {
                        setPaddingRelative(ui.dp(14), 0, ui.dp(8), 0)
                        addView(ui.label(item.title, 15, if (item.destructive) color else ui.ink))
                        item.detail?.let { addView(ui.label(it, 12, ui.muted)) }
                    }, LinearLayout.LayoutParams(0, WRAP, 1f))
                    if (item.opensPage) addView(ui.symbol(R.drawable.ic_portal_chevron, ui.muted),
                        LinearLayout.LayoutParams(ui.dp(17), ui.dp(17)))
                    isFocusable = true
                    isScreenReaderFocusable = true
                    isEnabled = item.enabled
                    alpha = if (item.enabled) 1f else .45f
                    setOnClickListener {
                        sheet.dialog.dismiss()
                        item.act()
                    }
                    accessibilityDelegate = object : View.AccessibilityDelegate() {
                        override fun onInitializeAccessibilityNodeInfo(
                            host: View,
                            info: android.view.accessibility.AccessibilityNodeInfo,
                        ) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.className = "android.widget.Button"
                        }
                    }
                })
            }
        }, 7)
    }
    sheet.show()
}

internal class PortalSheet(private val context: Context, private val ui: PortalViews, title: String) {
    val dialog = Dialog(context)
    val body = ui.column().apply {
        setPadding(ui.dp(18), 0, ui.dp(18), ui.dp(16))
    }
    private val root = ui.column().apply {
        background = ui.shape(ui.canvasColor, 24)
        setPadding(0, ui.dp(7), 0, 0)
        addView(ui.row().apply {
            setPadding(ui.dp(20), 0, ui.dp(7), 0)
            addView(ui.label(title, 21, ui.ink, bold = true).apply {
                isAccessibilityHeading = true
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(ui.iconAction(R.drawable.ic_chat_close, "Close $title", color = ui.muted) {
                dialog.dismiss()
            }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        })
        addView(ScrollView(context).apply {
            addView(body)
            clipToPadding = false
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(.48f)
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            @Suppress("DEPRECATION")
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.show()
        fit()
    }

    fun fit() {
        val config = context.resources.configuration
        val width = ui.dp((config.screenWidthDp - 12).coerceIn(230, 544))
        val maxHeight = ui.dp((config.screenHeightDp - 38).coerceAtLeast(160))
        val measuredHeight = View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), measuredHeight)
        dialog.window?.setLayout(width, root.measuredHeight)
    }
}
