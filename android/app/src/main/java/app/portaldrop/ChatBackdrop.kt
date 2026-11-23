package app.portaldrop

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable

internal class ChatBackdrop(context: Context) : Drawable() {
    private val base = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
        context.getColor(R.color.portal_wall_light),
        context.getColor(R.color.portal_wall_base),
        context.getColor(R.color.portal_wall_tail),
    ))
    private val density = context.resources.displayMetrics.density
    private val motifs = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.portal_wall_pattern)
        style = Paint.Style.STROKE
        strokeWidth = 1.05f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val motifAlpha = motifs.alpha
    private val linework = Path().apply {
        moveTo(13f, 23f); lineTo(29f, 16f); lineTo(23f, 32f)
        lineTo(20f, 26f); close()
        moveTo(20f, 26f); lineTo(29f, 16f)
        addCircle(91f, 25f, 5f, Path.Direction.CW)
        moveTo(68f, 65f); quadTo(76f, 53f, 85f, 65f)
        moveTo(20f, 92f); lineTo(34f, 92f)
        moveTo(27f, 85f); lineTo(27f, 99f)
        addRoundRect(94f, 87f, 107f, 103f, 3f, 3f, Path.Direction.CW)
        moveTo(98f, 92f); lineTo(103f, 92f)
        moveTo(98f, 97f); lineTo(103f, 97f)
        addCircle(57f, 106f, 2.3f, Path.Direction.CW)
        addCircle(45f, 53f, 1.7f, Path.Direction.CW)
        moveTo(111f, 53f); lineTo(116f, 58f)
        moveTo(116f, 53f); lineTo(111f, 58f)
    }

    override fun onBoundsChange(bounds: Rect) {
        base.bounds = bounds
    }

    override fun draw(canvas: Canvas) {
        base.draw(canvas)
        val height = bounds.height() / density
        val width = bounds.width() / density
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(density, density)
        var y = 0f
        while (y < height) {
            var x = 0f
            while (x < width) {
                val tile = canvas.save()
                canvas.translate(x, y)
                canvas.drawPath(linework, motifs)
                canvas.restoreToCount(tile)
                x += TILE
            }
            y += TILE
        }
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {
        base.alpha = alpha
        motifs.alpha = motifAlpha * alpha / 255
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        base.colorFilter = colorFilter
        motifs.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Pixel opacity is no longer queried by Android.")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    private companion object {
        const val TILE = 124f
    }
}
