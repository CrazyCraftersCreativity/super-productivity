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
 * small ones (top-right, bottom-left), with a blue edge.
 *  - open:     outline only
 *  - partial:  a translucent blue "tank" fills from the bottom, level = progress
 *  - done:     the whole petal is filled with very translucent blue
 *  - running:  stronger blue fill (the live clock is a Chronometer view on top)
 *  - paused:   dashed edge, frozen time and a pause sign
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

    private const val DISABLED_ALPHA = 115

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

        // Habits not scheduled for the displayed day are drawn faded and ignore taps.
        val layer = if (!habit.dayEnabled) {
            canvas.saveLayerAlpha(0f, 0f, w, h, DISABLED_ALPHA)
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

            display.isDone -> {
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
        val isPausedCountdown = habit.kind == HabitKind.COUNTDOWN && display.isPaused
        val half = edge / 2f
        val edgeRect = RectF(
            shape.left + half, shape.top + half, shape.right - half, shape.bottom - half
        )
        val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        edgePaint.style = Paint.Style.STROKE
        edgePaint.strokeWidth = edge
        edgePaint.color = colorBlue
        if (isPausedCountdown) {
            edgePaint.pathEffect = DashPathEffect(floatArrayOf(edge * 3f, edge * 2f), 0f)
        }
        canvas.drawPath(petalPath(edgeRect, big - half, small - half), edgePaint)

        // Centre content, sized relative to the petal's height
        val shh = shape.height()
        when {
            // The live clock is drawn by the Chronometer view above this bitmap.
            HabitWidgetData.usesChronometer(display) -> Unit

            // Finished yes/no habit: a check mark in place of its icon
            habit.kind == HabitKind.BOOL && display.isDone ->
                drawCheck(context, canvas, cx, cy, shh, colorAccent)

            // Paused countdown: the time that is left, plus a pause sign
            isPausedCountdown -> drawPaused(
                canvas, shape, colorInk, HabitWidgetData.formatClock(display.timeMs)
            )

            // Countdown that has run out at least once today: how many times
            habit.kind == HabitKind.COUNTDOWN && display.count > 0L && !display.isRunning ->
                drawCenteredText(
                    canvas, cx, cy, colorAccent, display.count.toString(),
                    shh * numberScale(display.count)
                )

            glyph != null -> drawGlyph(canvas, cx, cy, shh, colorAccent, glyph)

            // No icon (or a custom SVG icon that cannot be drawn here): first letter
            else -> drawCenteredText(
                canvas, cx, cy, colorAccent, HabitWidgetData.initialOf(habit.title), shh * 0.46f
            )
        }

        // Count / goal in the top-right
        val badge = HabitWidgetData.badgeText(display)
        if (badge.isNotEmpty()) {
            drawBadge(canvas, shape, badge, colorInk)
        }

        if (layer != -1) {
            canvas.restoreToCount(layer)
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

    private fun numberScale(count: Long): Float = when {
        count >= 1000L -> 0.30f
        count >= 100L -> 0.38f
        count >= 10L -> 0.46f
        else -> 0.54f
    }

    private fun drawGlyph(
        canvas: Canvas, cx: Float, cy: Float, shh: Float, color: Int, glyph: Bitmap
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        val half = shh * 0.23f
        canvas.drawBitmap(glyph, null, RectF(cx - half, cy - half, cx + half, cy + half), paint)
    }

    private fun drawCheck(
        context: Context, canvas: Canvas, cx: Float, cy: Float, shh: Float, color: Int
    ) {
        val drawable = context.getDrawable(R.drawable.ic_widget_habit_check)?.mutate() ?: return
        val half = (shh * 0.26f).toInt()
        drawable.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
        drawable.setTint(color)
        drawable.draw(canvas)
    }

    private fun textPaint(color: Int, textSize: Float, align: Paint.Align): Paint {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        paint.textSize = textSize
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = align
        return paint
    }

    /** Text centred on ([cx], [cy]); [textSize] is in pixels. */
    private fun drawCenteredText(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        color: Int,
        text: String,
        textSize: Float,
        yOffset: Float = 0f
    ) {
        val paint = textPaint(color, textSize, Paint.Align.CENTER)
        val metrics = paint.fontMetrics
        val baseline = cy + yOffset - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, cx, baseline, paint)
    }

    private fun drawPaused(canvas: Canvas, shape: RectF, color: Int, time: String) {
        val shh = shape.height()
        val cx = shape.centerX()
        val cy = shape.centerY()
        // Long times ("1:00:00") get a smaller font so they stay inside the petal.
        val size = minOf(shh * 0.30f, shape.width() * if (time.length > 5) 0.13f else 0.19f)
        drawCenteredText(canvas, cx, cy, color, time, size, yOffset = -shh * 0.07f)
        val bars = Paint(Paint.ANTI_ALIAS_FLAG)
        bars.color = color
        val barW = shh * 0.045f
        val barH = shh * 0.14f
        val top = cy + shh * 0.12f
        val gap = shh * 0.04f
        canvas.drawRoundRect(RectF(cx - gap - barW, top, cx - gap, top + barH), barW / 2f, barW / 2f, bars)
        canvas.drawRoundRect(RectF(cx + gap, top, cx + gap + barW, top + barH), barW / 2f, barW / 2f, bars)
    }

    /** Small number in the top-right corner ("3/8", "12m"), in the small-corner area. */
    private fun drawBadge(canvas: Canvas, shape: RectF, text: String, inkColor: Int) {
        val shh = shape.height()
        val paint = textPaint(inkColor, shh * 0.145f, Paint.Align.RIGHT)
        val top = shape.top + shh * 0.10f
        val baseline = top - paint.fontMetrics.ascent
        canvas.drawText(text, shape.right - shh * 0.17f, baseline, paint)
    }
}
