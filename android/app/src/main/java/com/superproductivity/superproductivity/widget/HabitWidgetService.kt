package com.superproductivity.superproductivity.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.superproductivity.superproductivity.R

class HabitWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return HabitRemoteViewsFactory(applicationContext)
    }
}

/**
 * Builds one tile per habit. Everything shown comes from [HabitWidgetData.display]:
 * the snapshot Angular wrote, plus taps still waiting in [HabitWidgetQueue].
 */
private class HabitRemoteViewsFactory(
    private val context: Context
) : RemoteViewsService.RemoteViewsFactory {

    private var items: List<HabitDisplay> = emptyList()
    private var icons: Map<String, String> = emptyMap()
    private val bitmapCache = HashMap<String, Bitmap?>()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        bitmapCache.clear()
        try {
            val snapshot = HabitWidgetProvider.loadSnapshot(context)
            icons = snapshot.icons
            items = HabitWidgetData.display(
                snapshot,
                HabitWidgetQueue.peekEvents(context),
                HabitWidgetQueue.runningTimers(context),
                System.currentTimeMillis()
            ).take(MAX_HABITS)
        } catch (e: Exception) {
            // Never log titles: they are user content.
            Log.e(TAG, "Failed to build habit tiles", e)
            items = emptyList()
        }
    }

    override fun onDestroy() {
        items = emptyList()
        bitmapCache.clear()
    }

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_habit_item)
        if (position >= items.size) {
            return rv
        }

        val display = items[position]
        val habit = display.habit
        val isFilled = display.isDone || display.isRunning

        // Ring: outline = open, brand fill = done, green fill = stopwatch running
        rv.setImageViewResource(
            R.id.widget_habit_ring,
            when {
                display.isRunning -> R.drawable.widget_habit_circle_running
                display.isDone -> R.drawable.widget_habit_circle_done
                else -> R.drawable.widget_habit_circle_open
            }
        )
        val alpha = if (habit.dayEnabled) 255 else DISABLED_ALPHA
        rv.setInt(R.id.widget_habit_ring, "setImageAlpha", alpha)

        val foreground = if (isFilled) Color.WHITE else context.getColor(R.color.widget_ink)

        // Centre: a finished yes/no habit shows a check mark in place of its icon;
        // otherwise the habit's own icon, or its first letter if it has none (or one
        // the app draws from a custom SVG, which cannot be rendered here).
        val showCheck = habit.kind == HabitKind.BOOL && display.isDone
        val bitmap = if (showCheck) null else iconBitmap(habit.icon)
        when {
            showCheck -> {
                rv.setImageViewResource(R.id.widget_habit_icon, R.drawable.ic_widget_habit_check)
                showIcon(rv, foreground, alpha)
            }

            bitmap != null -> {
                rv.setImageViewBitmap(R.id.widget_habit_icon, bitmap)
                showIcon(rv, foreground, alpha)
            }

            else -> {
                rv.setViewVisibility(R.id.widget_habit_icon, View.GONE)
                rv.setViewVisibility(R.id.widget_habit_letter, View.VISIBLE)
                rv.setTextViewText(R.id.widget_habit_letter, HabitWidgetData.initialOf(habit.title))
                rv.setTextColor(
                    R.id.widget_habit_letter,
                    Color.argb(alpha, Color.red(foreground), Color.green(foreground), Color.blue(foreground))
                )
            }
        }
        if (bitmap != null || showCheck) {
            rv.setViewVisibility(R.id.widget_habit_letter, View.GONE)
        }

        // Small count / time label
        val badge = HabitWidgetData.badgeText(display)
        if (badge.isEmpty()) {
            rv.setViewVisibility(R.id.widget_habit_badge, View.GONE)
        } else {
            rv.setViewVisibility(R.id.widget_habit_badge, View.VISIBLE)
            rv.setTextViewText(R.id.widget_habit_badge, badge)
        }

        rv.setViewVisibility(
            R.id.widget_habit_state,
            if (display.isRunning) View.VISIBLE else View.GONE
        )

        rv.setContentDescription(R.id.widget_habit_item, habit.title)
        // Habits not scheduled for the displayed day ignore taps, like in the app.
        if (habit.dayEnabled) {
            rv.setOnClickFillInIntent(
                R.id.widget_habit_item,
                Intent().putExtra(HabitWidgetActionReceiver.EXTRA_HABIT_ID, habit.id)
            )
        }
        return rv
    }

    private fun showIcon(rv: RemoteViews, color: Int, alpha: Int) {
        rv.setViewVisibility(R.id.widget_habit_icon, View.VISIBLE)
        rv.setInt(R.id.widget_habit_icon, "setColorFilter", color)
        rv.setInt(R.id.widget_habit_icon, "setImageAlpha", alpha)
    }

    /** Decodes (and caches per refresh) the PNG Angular rendered for an icon name. */
    private fun iconBitmap(name: String?): Bitmap? {
        if (name == null) return null
        if (bitmapCache.containsKey(name)) return bitmapCache[name]
        val bitmap = try {
            val encoded = icons[name]
            if (encoded == null) {
                null
            } else {
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (e: Exception) {
            null
        }
        bitmapCache[name] = bitmap
        return bitmap
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = false

    companion object {
        private const val TAG = "HabitWidget"
        private const val MAX_HABITS = 60
        private const val DISABLED_ALPHA = 90
    }
}
