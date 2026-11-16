package com.superproductivity.superproductivity.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import com.superproductivity.superproductivity.R

/**
 * Draws one habit tile (ring, centre content, corner badge) into a square bitmap.
 *
 * The widget shows this bitmap in an ImageView with fitCenter, so it always grows to
 * the largest square that fits the tile's cell: the icon is exactly as big as one tile
 * on any device, and everything inside (ring, glyph, text) scales together.
 *
 * Not drawn here: the running clock of a stopwatch / countdown. That is a live
 * Chronometer view on top of the tile (a widget cannot redraw itself every second).
 */
object HabitTileRenderer {
    /** Ring diameter relative to the tile; the rest leaves room for the refresh button. */
    private const val RING_SCALE = 0.84f
    private const val DISABLED_ALPHA = 90

    fun render(
        context: Context,
        display: HabitDisplay,
        glyph: Bitmap?,
        sizePx: Int
    ): Bitmap {
        val size = sizePx.toFloat()
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val habit = display.habit

        val colorInk = context.getColor(R.color.widget_ink)
        val colorBrand = context.getColor(R.color.widget_brand)
        val colorSurface = context.getColor(R.color.widget_bg)
        val colorSeparator = context.getColor(R.color.widget_separator)
        val colorRunning = context.getColor(R.color.widget_habit_running)

        // Habits not scheduled for the displayed day are drawn faded and ignore taps.
        val layer = if (!habit.dayEnabled) {
            canvas.saveLayerAlpha(0f, 0f, size, size, DISABLED_ALPHA)
        } else {
            -1
        }

        val cx = size / 2f
        val cy = size / 2f
        val radius = size * RING_SCALE / 2f
        val strokeWidth = size * 0.05f

        // Ring: outline = open, brand fill = done, green fill = a timer is running
        val isFilled = display.isDone || display.isRunning
        val fg = if (isFilled) Color.WHITE else colorInk
        val ring = Paint(Paint.ANTI_ALIAS_FLAG)
        when {
            display.isRunning -> {
                ring.style = Paint.Style.FILL
                ring.color = colorRunning
                canvas.drawCircle(cx, cy, radius, ring)
            }

            display.isDone -> {
                ring.style = Paint.Style.FILL
                ring.color = colorBrand
                canvas.drawCircle(cx, cy, radius, ring)
            }

            else -> {
                ring.style = Paint.Style.FILL
                ring.color = colorSurface
                canvas.drawCircle(cx, cy, radius, ring)
                ring.style = Paint.Style.STROKE
                ring.strokeWidth = strokeWidth
                ring.color = colorBrand
                canvas.drawCircle(cx, cy, radius - strokeWidth / 2f, ring)
            }
        }

        // Centre content
        when {
            // The live clock is drawn by the Chronometer view above this bitmap.
            HabitWidgetData.usesChronometer(display) -> Unit

            // Finished yes/no habit: a check mark in place of its icon
            habit.kind == HabitKind.BOOL && display.isDone -> drawCheck(context, canvas, size, fg)

            // Paused countdown: the time that is left, plus a pause sign
            habit.kind == HabitKind.COUNTDOWN && display.isPaused ->
                drawPaused(canvas, size, fg, HabitWidgetData.formatClock(display.timeMs))

            // Countdown that has run out at least once today: how many times
            habit.kind == HabitKind.COUNTDOWN && display.count > 0L && !display.isRunning ->
                drawCenteredText(canvas, size, fg, display.count.toString(), numberScale(display.count))

            glyph != null -> drawGlyph(canvas, size, fg, glyph)

            // No icon (or a custom SVG icon that cannot be drawn here): first letter
            else -> drawCenteredText(canvas, size, fg, HabitWidgetData.initialOf(habit.title), 0.42f)
        }

        // Corner badge ("3", "3/8", "12m", "/3")
        val badge = HabitWidgetData.badgeText(display)
        if (badge.isNotEmpty()) {
            drawBadge(canvas, size, badge, colorInk, colorSurface, colorSeparator)
        }

        if (layer != -1) {
            canvas.restoreToCount(layer)
        }
        return bitmap
    }

    private fun numberScale(count: Long): Float = when {
        count >= 1000L -> 0.28f
        count >= 100L -> 0.36f
        count >= 10L -> 0.46f
        else -> 0.54f
    }

    private fun drawGlyph(canvas: Canvas, size: Float, color: Int, glyph: Bitmap) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        val half = size * 0.27f
        val rect = RectF(size / 2f - half, size / 2f - half, size / 2f + half, size / 2f + half)
        canvas.drawBitmap(glyph, null, rect, paint)
    }

    private fun drawCheck(context: Context, canvas: Canvas, size: Float, color: Int) {
        val drawable = context.getDrawable(R.drawable.ic_widget_habit_check)?.mutate() ?: return
        val half = (size * 0.30f).toInt()
        val c = (size / 2f).toInt()
        drawable.setBounds(c - half, c - half, c + half, c + half)
        drawable.setTint(color)
        drawable.draw(canvas)
    }

    private fun textPaint(color: Int, textSize: Float): Paint {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        paint.textSize = textSize
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        return paint
    }

    /** Text centred in the tile; [scale] is the text size relative to the tile. */
    private fun drawCenteredText(
        canvas: Canvas,
        size: Float,
        color: Int,
        text: String,
        scale: Float,
        yOffset: Float = 0f
    ) {
        val paint = textPaint(color, size * scale)
        val metrics = paint.fontMetrics
        val baseline = size / 2f + yOffset - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, size / 2f, baseline, paint)
    }

    private fun drawPaused(canvas: Canvas, size: Float, color: Int, time: String) {
        // Long times ("1:00:00") get a smaller font so they stay inside the ring.
        val scale = if (time.length > 5) 0.19f else 0.25f
        drawCenteredText(canvas, size, color, time, scale, yOffset = -size * 0.05f)
        val bars = Paint(Paint.ANTI_ALIAS_FLAG)
        bars.color = color
        val barW = size * 0.045f
        val barH = size * 0.13f
        val top = size / 2f + size * 0.12f
        val gap = size * 0.04f
        val c = size / 2f
        canvas.drawRoundRect(RectF(c - gap - barW, top, c - gap, top + barH), barW / 2f, barW / 2f, bars)
        canvas.drawRoundRect(RectF(c + gap, top, c + gap + barW, top + barH), barW / 2f, barW / 2f, bars)
    }

    private fun drawBadge(
        canvas: Canvas,
        size: Float,
        text: String,
        inkColor: Int,
        surfaceColor: Int,
        separatorColor: Int
    ) {
        val textSize = size * 0.17f
        val paint = textPaint(inkColor, textSize)
        val padX = textSize * 0.45f
        val width = paint.measureText(text) + padX * 2f
        val height = textSize * 1.45f
        val right = size - size * 0.02f
        val bottom = size - size * 0.02f
        val rect = RectF(right - width, bottom - height, right, bottom)

        val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        bg.style = Paint.Style.FILL
        bg.color = surfaceColor
        canvas.drawRoundRect(rect, height / 2f, height / 2f, bg)
        bg.style = Paint.Style.STROKE
        bg.strokeWidth = size * 0.012f
        bg.color = separatorColor
        canvas.drawRoundRect(rect, height / 2f, height / 2f, bg)

        val metrics = paint.fontMetrics
        val baseline = rect.centerY() - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, rect.centerX(), baseline, paint)
    }
}
