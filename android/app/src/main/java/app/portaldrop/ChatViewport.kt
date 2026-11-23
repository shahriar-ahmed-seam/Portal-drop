package app.portaldrop

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.ListView

internal class ChatViewport(context: Context, private val tracksLatest: Boolean) : ListView(context) {
    private data class Anchor(val id: Long, val top: Int)
    private data class Position(val latest: Boolean, val anchors: List<Anchor>)

    private var pendingPosition: Position? = null
    private val endTolerance = (24 * resources.displayMetrics.density).toInt()

    fun isAtLatest(viewportHeight: Int = height): Boolean {
        if (count == 0) return true
        if (childCount == 0 || lastVisiblePosition != count - 1) return false
        return getChildAt(childCount - 1).bottom <= viewportHeight - paddingBottom + endTolerance
    }

    fun showLatest() {
        if (!tracksLatest) return
        pendingPosition = Position(latest = true, anchors = emptyList())
        requestLayout()
    }

    fun updateContent(prepending: Boolean, update: () -> Boolean) {
        val previous = pendingPosition ?: position()
        if (!update()) return
        pendingPosition = if (prepending && previous.anchors.isNotEmpty()) {
            Position(latest = false, anchors = previous.anchors.sortedBy { it.id <= 0 })
        } else previous
        requestLayout()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        if ((oldHeight != height || oldWidth != width) && oldHeight > 0 && pendingPosition == null) {
            pendingPosition = position(oldHeight)
        }
        super.onSizeChanged(width, height, oldWidth, oldHeight)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) pendingPosition = null
        return super.dispatchTouchEvent(event)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val destination = pendingPosition
        pendingPosition = null
        val rows = adapter as? ChatTimelineAdapter
        if (destination != null && rows != null && count > 0) {
            if (destination.latest) {
                alignLastRow(rows, right - left, bottom - top)
            } else {
                for (anchor in destination.anchors) {
                    val position = rows.positionOf(anchor.id)
                    if (position >= 0) {
                        setSelectionFromTop(position, anchor.top)
                        break
                    }
                }
            }
        }
        super.onLayout(changed, left, top, right, bottom)
    }

    private fun position(viewportHeight: Int = height): Position = Position(
        latest = tracksLatest && (isAtLatest(viewportHeight) || childCount == 0),
        anchors = (0 until childCount).mapNotNull { index ->
            val child = getChildAt(index)
            val id = child.tag as? Long ?: return@mapNotNull null
            Anchor(id, child.top - paddingTop)
        },
    )

    private fun alignLastRow(rows: ChatTimelineAdapter, viewportWidth: Int, viewportHeight: Int) {
        val width = viewportWidth - paddingLeft - paddingRight
        val height = viewportHeight - paddingTop - paddingBottom
        if (width <= 0 || height <= 0) return
        val last = rows.count - 1
        val row = rows.getView(last, null, this)
        row.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        setSelectionFromTop(last, height - row.measuredHeight)
    }
}
