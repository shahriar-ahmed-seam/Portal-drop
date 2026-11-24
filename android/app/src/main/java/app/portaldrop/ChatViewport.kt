package app.portaldrop

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ListView
import kotlin.math.abs

internal class ChatViewport(
    context: Context,
    private val tracksLatest: Boolean,
    private val onSwipeReply: ((ChatItem) -> Unit)? = null,
) : ListView(context) {
    private data class Anchor(val id: Long, val top: Int)
    private data class Position(val latest: Boolean, val anchors: List<Anchor>)
    private data class Swipe(
        val row: ChatItem, val view: View, val x: Float, val y: Float,
        var dragging: Boolean = false, var ready: Boolean = false,
    )

    private var pendingPosition: Position? = null
    private val endTolerance = (24 * resources.displayMetrics.density).toInt()
    private val replyDistance = 56 * resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var swipe: Swipe? = null

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
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            pendingPosition = null
            finishSwipe()
            if (onSwipeReply != null) {
                val position = pointToPosition(event.x.toInt(), event.y.toInt())
                val row = (adapter as? ChatTimelineAdapter)?.replyCandidate(position)
                val child = getChildAt(position - firstVisiblePosition)
                if (row != null && child != null) swipe = Swipe(row, child, event.x, event.y)
            }
        }
        val candidate = swipe ?: return super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            val left = candidate.x - event.x
            val vertical = abs(event.y - candidate.y)
            if (!candidate.dragging) {
                if (vertical > touchSlop && vertical >= abs(left) ||
                    event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout()) {
                    finishSwipe()
                } else if (left > touchSlop && left > vertical * 1.5f) {
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    candidate.dragging = true
                }
            }
            if (candidate.dragging) {
                candidate.view.translationX = -left.coerceIn(0f, replyDistance * 1.25f)
                val ready = left >= replyDistance
                if (ready && !candidate.ready) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                candidate.ready = ready
                return true
            }
        } else if (event.actionMasked != MotionEvent.ACTION_DOWN) {
            val sendReply = event.actionMasked == MotionEvent.ACTION_UP && candidate.ready &&
                candidate.view.parent === this && candidate.view.tag == candidate.row.seq
            finishSwipe()
            if (sendReply) onSwipeReply?.invoke(candidate.row)
            if (candidate.dragging) return true
        }
        return super.dispatchTouchEvent(event)
    }

    private fun finishSwipe() {
        swipe?.view?.translationX = 0f
        swipe = null
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
