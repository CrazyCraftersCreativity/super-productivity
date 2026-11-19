package com.superproductivity.superproductivity.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import com.superproductivity.superproductivity.R

/**
 * Draws one habit tile into a bitmap that has the size and shape of one launcher cell.
 *
 * The tile is a wide "petal": two large rounded corners (top-left, bottom-right) and two
 * small ones (top-right, bottom-left), with a blue edge. The habit's own icon is in the
 * middle in every state; the exact value (count/goal, or a timer) is the number in the
 * top-right corner.
 *  - open:     outline only
 *  - partial:  a translucent blue "tank" fills from the bottom, level = progress
 *  - running:  stronger blue fill (the live clock is a Chronometer view in the top-right)
 *  - paused:   dashed edge, a pause sign under the icon, the time left top-right
 *  - done:     the whole tile faded, with a full-strength check on top
 *
 * The widget shows the bitmap in an ImageView with fitCenter. Because the bitmap has the
 * cell's own proportions, the petal fills any cell, wide or tall.
 */
object HabitTileRenderer {
    // Margins around the petal, relative to the cell
    private const val INSET_X = 0.045f
    private const val INSET_Y = 0.076f

    // Corner radii and edge width, relative to the shorter side of the petal
    private const val BIG_CORNER = 0.5f
    private const val SMALL_CORNER = 0.14f
    private const val EDGE = 0.032f

    // Wave on the surface of the tank, relative to the petal's shorter side
    private const val WAVE = 0.035f

    // Top-right number, relative to the petal's height
    private const val BADGE_SIZE = 0.145f
    private const val BADGE_TOP = 0.10f
    private const val BADGE_RIGHT = 0.17f

    /** Faded habits (not scheduled today, or done) keep this much of their strength. */
    private const val FADED_ALPHA = 115

    /**
     * Where the top-right number sits in a cell of [cellWidth] x [cellHeight] (any unit):
     * its text size and its distance from the cell's top and right edge. The live clock
     * of a running timer is a separate view and uses this to line up with the bitmap.
     */
    class BadgeSpec(val textSize: Float, val topInset: Float, val rightInset: Float)

    fun badgeSpec(cellWidth: Float, cellHeight: Float): BadgeSpec {
        val shapeHeight = cellHeight * (1f - 2f * INSET_Y)
        return BadgeSpec(
            textSize = shapeHeight * BADGE_SIZE,
            topInset = cellHeight * INSET_Y + shapeHeight * BADGE_TOP,
            rightInset = cellWidth * INSET_X + shapeHeight * BADGE_RIGHT
        )
    }

    fun render(
        context: Context,
        display: HabitDisplay,
        glyph: Bitmap?,
        widthPx: Int,
        heightPx: Int
    ): Bitmap {
        val w = widthPx.toFloat()
        val h = heightPx.toFloat()
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val habit = display.habit

        val colorInk = context.getColor(R.color.widget_ink)
        val colorSurface = context.getColor(R.color.widget_bg)
        val colorBlue = context.getColor(R.color.habit_blue)
        val colorAccent = context.getColor(R.color.habit_accent)
        val fillLiquid = context.getColor(R.color.habit_fill_liquid)
        val fillDone = context.getColor(R.color.habit_fill_done)
        val fillRunning = context.getColor(R.color.habit_fill_running)

        // A finished habit stays faded; a timer that is still going is never faded.
        val isFinished = display.isDone && !display.isRunning && !display.isPaused
        // Not-scheduled habits and finished habits are drawn faded. Not-scheduled ones
        // also ignore taps; finished ones get the check below.
        val layer = if (!habit.dayEnabled || isFinished) {
            canvas.saveLayerAlpha(0f, 0f, w, h, FADED_ALPHA)
        } else {
            -1
        }

        // The petal
        val shape = RectF(w * INSET_X, h * INSET_Y, w - w * INSET_X, h - h * INSET_Y)
        val m = minOf(shape.width(), shape.height())
        val big = m * BIG_CORNER
        val small = m * SMALL_CORNER
        val edge = maxOf(m * EDGE, 2f)
        val cx = shape.centerX()
        val cy = shape.centerY()
        val shapePath = petalPath(shape, big, small)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL
        paint.color = colorSurface
        canvas.drawPath(shapePath, paint)

        // Everything below is clipped to the petal.
        canvas.save()
        canvas.clipPath(shapePath)
        when {
            display.isRunning -> {
                paint.color = fillRunning
                canvas.drawRect(shape, paint)
            }

            isFinished -> {
                paint.color = fillDone
                canvas.drawRect(shape, paint)
            }

            else -> {
                val ratio = progress(display)
                if (ratio > 0f) {
                    paint.color = fillLiquid
                    canvas.drawPath(tankPath(shape, edge, ratio, m * WAVE), paint)
                }
            }
        }
        canvas.restore()

        // Edge: drawn inside the petal's outline; dashed while a countdown is paused.
        val half = edge / 2f
        val edgeRect = RectF(
            shape.left + half, shape.top + half, shape.right - half, shape.bottom - half
        )
        val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        edgePaint.style = Paint.Style.STROKE
        edgePaint.strokeWidth = edge
        edgePaint.color = colorBlue
        if (display.isPaused) {
            edgePaint.pathEffect = DashPathEffect(floatArrayOf(edge * 3f, edge * 2f), 0f)
        }
        canvas.drawPath(petalPath(edgeRect, big - half, small - half), edgePaint)

        // The habit's icon in the middle, in every state. Sized by the petal's height.
        val shh = shape.height()
        if (glyph != null) {
            drawGlyph(canvas, cx, cy, shh, colorAccent, glyph)
        } else {
            // No icon (or a custom SVG icon that cannot be drawn here): first letter
            drawLetter(canvas, cx, cy, colorAccent, HabitWidgetData.initialOf(habit.title), shh * 0.46f)
        }

        if (display.isPaused) {
            drawPauseSign(canvas, shape, edge, colorInk)
        }

        // Count/goal or timer in the top-right
        val badge = HabitWidgetData.badgeText(display)
        if (badge.isNotEmpty()) {
            drawBadge(canvas, shape, badge, colorInk)
        }

        if (layer != -1) {
            canvas.restoreToCount(layer)
        }

        // The check is drawn after the faded layer is closed, so it keeps full strength.
        if (isFinished && habit.dayEnabled) {
            drawCheck(canvas, cx, cy, shh * 0.5f, colorAccent, colorSurface)
        }
        return bitmap
    }

    /** Share of the goal reached, for the tank. Zero when done, running or goal-less. */
    private fun progress(display: HabitDisplay): Float {
        val goal = display.habit.goal
        if (display.isDone || display.isRunning || goal <= 1L) return 0f
        return (display.count.toFloat() / goal.toFloat()).coerceIn(0f, 1f)
    }

    /** Rounded rectangle with big top-left / bottom-right and small other corners. */
    private fun petalPath(rect: RectF, big: Float, small: Float): Path {
        val b = maxOf(big, 0f)
        val s = maxOf(small, 0f)
        val path = Path()
        // Order: top-left, top-right, bottom-right, bottom-left (x and y radius each)
        path.addRoundRect(rect, floatArrayOf(b, b, s, s, b, b, s, s), Path.Direction.CW)
        return path
    }

    /** The liquid: everything below a gently waving surface at [ratio] of the inner height. */
    private fun tankPath(shape: RectF, edge: Float, ratio: Float, amp: Float): Path {
        val innerBottom = shape.bottom - edge
        val innerHeight = shape.height() - edge * 2f
        val surface = innerBottom - ratio * innerHeight
        val left = shape.left
        val right = shape.right
        val width = right - left
        val path = Path()
        path.moveTo(left, surface)
        path.quadTo(left + width / 4f, surface - 2f * amp, left + width / 2f, surface)
        path.quadTo(left + width * 3f / 4f, surface + 2f * amp, right, surface)
        path.lineTo(right, shape.bottom)
        path.lineTo(left, shape.bottom)
        path.close()
        return path
    }

    private fun drawGlyph(
        canvas: Canvas, cx: Float, cy: Float, shh: Float, color: Int, glyph: Bitmap
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        val half = shh * 0.23f
        canvas.drawBitmap(glyph, null, RectF(cx - half, cy - half, cx + half, cy + half), paint)
    }

    /** A bold check with a thin halo in the tile colour, so it reads over the icon. */
    private fun drawCheck(
        canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int, haloColor: Int
    ) {
        val unit = size / 24f
        val left = cx - size / 2f
        val top = cy - size / 2f
        val path = Path()
        path.moveTo(left + 20f * unit, top + 6f * unit)
        path.lineTo(left + 9f * unit, top + 17f * unit)
        path.lineTo(left + 4f * unit, top + 12f * unit)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = haloColor
        paint.strokeWidth = 6f * unit
        canvas.drawPath(path, paint)
        paint.color = color
        paint.strokeWidth = 3f * unit
        canvas.drawPath(path, paint)
    }

    private fun textPaint(color: Int, textSize: Float, align: Paint.Align): Paint {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        paint.textSize = textSize
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = align
        return paint
    }

    private fun drawLetter(
        canvas: Canvas, cx: Float, cy: Float, color: Int, text: String, textSize: Float
    ) {
        val paint = textPaint(color, textSize, Paint.Align.CENTER)
        val metrics = paint.fontMetrics
        canvas.drawText(text, cx, cy - (metrics.ascent + metrics.descent) / 2f, paint)
    }

    /** Two small bars under the icon, at the bottom of the petal. */
    private fun drawPauseSign(canvas: Canvas, shape: RectF, edge: Float, color: Int) {
        val shh = shape.height()
        val cx = shape.centerX()
        val bars = Paint(Paint.ANTI_ALIAS_FLAG)
        bars.color = color
        val barW = shh * 0.032f
        val barH = shh * 0.115f
        val gap = shh * 0.024f
        val bottom = shape.bottom - edge - shh * 0.09f
        val top = bottom - barH
        canvas.drawRoundRect(RectF(cx - gap - barW, top, cx - gap, bottom), barW / 2f, barW / 2f, bars)
        canvas.drawRoundRect(RectF(cx + gap, top, cx + gap + barW, bottom), barW / 2f, barW / 2f, bars)
    }

    /** The number in the top-right corner (count/goal, or a timer). */
    private fun drawBadge(canvas: Canvas, shape: RectF, text: String, inkColor: Int) {
        val shh = shape.height()
        val paint = textPaint(inkColor, shh * BADGE_SIZE, Paint.Align.RIGHT)
        val top = shape.top + shh * BADGE_TOP
        val baseline = top - paint.fontMetrics.ascent
        canvas.drawText(text, shape.right - shh * BADGE_RIGHT, baseline, paint)
    }
}
