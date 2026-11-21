package com.superproductivity.superproductivity.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.superproductivity.superproductivity.App
import com.superproductivity.superproductivity.CapacitorMainActivity
import com.superproductivity.superproductivity.R

/**
 * Home screen widget showing one tile per habit, read from the `widget_data_habits`
 * snapshot pushed by Angular. One tile is one launcher cell: the widget is divided into
 * as many columns and rows as it spans cells, and each habit tile (a wide blue petal,
 * see [HabitTileRenderer]) fills its cell. Habits that do not fit are not shown; extra space stays empty.
 *
 * Taps are not handled here — they go to [HabitWidgetActionReceiver], which records
 * them for Angular. The widget is never "live": it shows the last snapshot plus the taps
 * queued since (the only live parts are the Chronometer clocks of running timers).
 * Once the snapshot's day is over (past midnight) it says so, starts the new day from
 * zero, and waits for the refresh button (or any app launch) to bring fresh data.
 */
class HabitWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        updateAll(context, appWidgetManager, appWidgetIds)
    }

    /** The user resized the widget: the number of columns and rows changes. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateAll(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // Last habits widget removed: no more wake-ups needed.
        cancelWakeAlarm(context)
    }

    companion object {
        private const val TAG = "HabitWidget"

        // Distinct request codes: PendingIntents are matched without their extras, so
        // reusing the task widget's code 0 for the same activity would overwrite it.
        private const val REQ_REFRESH = 7301
        private const val REQ_OPEN_APP = 7302
        private const val REQ_TILE = 7303
        private const val REQ_WAKE_ALARM = 7304

        /** Redraw this long after midnight, so the new day has certainly begun. */
        private const val STALE_ALARM_SLACK_MS = 1_000L

        // Android's documented widget sizing: a widget n cells wide is about 70n - 30 dp.
        private const val CELL_DP = 70f
        private const val CELL_MARGIN_DP = 30f
        private const val DEFAULT_WIDTH_DP = 180
        private const val DEFAULT_HEIGHT_DP = 40
        private const val MAX_COLS = 12
        private const val MAX_ROWS = 8
        private const val MAX_TILES = 36

        /**
         * A tile bitmap has the cell's proportions; its area is limited so that many tiles
         * stay within the memory a widget may send to the launcher.
         */
        private const val MIN_TILE_PX = 64
        private const val MAX_TILE_AREA_PX = 64_000

        private const val TILE_URI_PREFIX = "habitwidget://tap/"

        private fun widgetIds(context: Context, appWidgetManager: AppWidgetManager): IntArray =
            appWidgetManager.getAppWidgetIds(
                ComponentName(context, HabitWidgetProvider::class.java)
            )

        /** Reads the snapshot Angular wrote; an unreadable one counts as "no habits". */
        fun loadSnapshot(context: Context): HabitSnapshot {
            return try {
                HabitWidgetData.parseSnapshot(
                    (context.applicationContext as App).keyValStore
                        .get(HabitWidgetData.KEYVAL_KEY, "{}")
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read habit widget data", e)
                HabitWidgetData.EMPTY
            }
        }

        /** Redraws every placed habits widget. Called after Angular pushes and after taps. */
        fun refreshAll(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = widgetIds(context, appWidgetManager)
            if (ids.isEmpty()) {
                return
            }
            updateAll(context, appWidgetManager, ids)
        }

        private fun updateAll(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray
        ) {
            val now = System.currentTimeMillis()
            val snapshot = loadSnapshot(context)
            val isStale = HabitWidgetData.isStale(snapshot.validUntil, now)
            val displays = HabitWidgetData.display(
                snapshot,
                HabitWidgetQueue.peekEvents(context),
                HabitWidgetQueue.runningTimers(context),
                HabitWidgetQueue.countdowns(context),
                now
            )
            val glyphs = HashMap<String, Bitmap?>()
            for (appWidgetId in appWidgetIds) {
                try {
                    updateWidget(
                        context, appWidgetManager, appWidgetId, displays, snapshot.icons,
                        glyphs, isStale
                    )
                } catch (e: Exception) {
                    // One broken widget must not stop the others (or the alarm below).
                    Log.e(TAG, "Failed to update habits widget", e)
                }
            }
            scheduleStaleAlarm(context, snapshot.validUntil)
        }

        /** Columns, rows and the size (dp) of one cell of a widget, from its reported size. */
        private class GridMetrics(
            val cols: Int,
            val rows: Int,
            val cellWidthDp: Float,
            val cellHeightDp: Float
        )

        private fun gridMetrics(context: Context, options: Bundle?): GridMetrics {
            val isLandscape =
                context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            // The launcher reports a range of sizes; which end applies depends on rotation.
            var widthDp = options?.getInt(
                if (isLandscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
                else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
                0
            ) ?: 0
            var heightDp = options?.getInt(
                if (isLandscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
                else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,
                0
            ) ?: 0
            if (widthDp <= 0) widthDp = DEFAULT_WIDTH_DP
            if (heightDp <= 0) heightDp = DEFAULT_HEIGHT_DP
            val cols = ((widthDp + CELL_MARGIN_DP) / CELL_DP + 0.2f).toInt().coerceIn(1, MAX_COLS)
            val rows = ((heightDp + CELL_MARGIN_DP) / CELL_DP + 0.2f).toInt().coerceIn(1, MAX_ROWS)
            return GridMetrics(cols, rows, widthDp.toFloat() / cols, heightDp.toFloat() / rows)
        }

        /** Decodes (and caches per update) the PNG Angular rendered for an icon name. */
        private fun glyphFor(
            name: String?,
            icons: Map<String, String>,
            cache: HashMap<String, Bitmap?>
        ): Bitmap? {
            if (name == null) return null
            if (cache.containsKey(name)) return cache[name]
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
            cache[name] = bitmap
            return bitmap
        }

        private fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            displays: List<HabitDisplay>,
            icons: Map<String, String>,
            glyphCache: HashMap<String, Bitmap?>,
            isStale: Boolean
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_habit_list)
            val metrics = gridMetrics(context, appWidgetManager.getAppWidgetOptions(appWidgetId))
            val density = context.resources.displayMetrics.density

            val shown = displays.take(minOf(metrics.cols * metrics.rows, MAX_TILES))
            var tileW = metrics.cellWidthDp * density
            var tileH = metrics.cellHeightDp * density
            if (tileW * tileH > MAX_TILE_AREA_PX) {
                val scale = Math.sqrt(MAX_TILE_AREA_PX / (tileW * tileH).toDouble()).toFloat()
                tileW *= scale
                tileH *= scale
            }
            val tileWidthPx = tileW.toInt().coerceAtLeast(MIN_TILE_PX)
            val tileHeightPx = tileH.toInt().coerceAtLeast(MIN_TILE_PX)
            // The live clock of a running timer sits exactly where the bitmap draws its
            // top-right number.
            val badge = HabitTileRenderer.badgeSpec(metrics.cellWidthDp, metrics.cellHeightDp)

            // The grid is rebuilt from scratch. It always has every row and column (empty
            // cells included) so each tile keeps the size of one cell even when there are
            // fewer habits than cells.
            views.removeAllViews(R.id.widget_habit_rows)
            var index = 0
            for (r in 0 until metrics.rows) {
                val row = RemoteViews(context.packageName, R.layout.widget_habit_row)
                for (c in 0 until metrics.cols) {
                    val tile = RemoteViews(context.packageName, R.layout.widget_habit_item)
                    if (index < shown.size) {
                        bindTile(
                            context, tile, shown[index], icons, glyphCache,
                            tileWidthPx, tileHeightPx, badge, density
                        )
                    }
                    row.addView(R.id.widget_habit_row, tile)
                    index++
                }
                views.addView(R.id.widget_habit_rows, row)
            }

            val openAppIntent = Intent(context, CapacitorMainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                context, REQ_OPEN_APP, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setViewVisibility(
                R.id.widget_habit_empty,
                if (displays.isEmpty()) View.VISIBLE else View.GONE
            )
            views.setOnClickPendingIntent(R.id.widget_habit_empty, openAppPendingIntent)

            // Refresh button: top-left corner. Opens the app with a flag; the app closes
            // itself again after it has pushed fresh data (see HabitWidgetRefreshGate).
            val refreshIntent = Intent(context, CapacitorMainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(HabitWidgetRefreshGate.EXTRA_REFRESH_AND_CLOSE, true)
            }
            val refreshPendingIntent = PendingIntent.getActivity(
                context, REQ_REFRESH, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_habit_refresh, refreshPendingIntent)
            views.setContentDescription(
                R.id.widget_habit_refresh,
                context.getString(R.string.widget_habits_refresh)
            )

            // Outdated statement: the button turns amber at every size and, when there is
            // room for it (two or more columns), grows an "Outdated" label.
            views.setInt(
                R.id.widget_habit_refresh,
                "setBackgroundResource",
                if (isStale) R.drawable.widget_habit_pill_outdated
                else R.drawable.widget_habit_pill_open
            )
            views.setInt(
                R.id.widget_habit_refresh_icon,
                "setColorFilter",
                if (isStale) context.getColor(R.color.habit_on_outdated)
                else context.getColor(R.color.habit_accent)
            )
            views.setViewVisibility(
                R.id.widget_habit_banner,
                if (isStale && metrics.cols >= 2) View.VISIBLE else View.GONE
            )

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        /** Fills one tile: its bitmap, the live clock if a timer runs, and the tap. */
        private fun bindTile(
            context: Context,
            tile: RemoteViews,
            display: HabitDisplay,
            icons: Map<String, String>,
            glyphCache: HashMap<String, Bitmap?>,
            tileWidthPx: Int,
            tileHeightPx: Int,
            badge: HabitTileRenderer.BadgeSpec,
            density: Float
        ) {
            val habit = display.habit
            tile.setImageViewBitmap(
                R.id.widget_habit_tile,
                HabitTileRenderer.render(
                    context, display, glyphFor(habit.icon, icons, glyphCache),
                    tileWidthPx, tileHeightPx
                )
            )

            // Live clock (top-right). A running stopwatch counts up from the time tracked today, a
            // running countdown counts down to zero. Both tick inside the launcher, with
            // no widget update per second. Hidden (and stopped) otherwise.
            if (HabitWidgetData.usesChronometer(display)) {
                val isCountDown = habit.kind == HabitKind.COUNTDOWN
                val base = if (isCountDown) {
                    SystemClock.elapsedRealtime() + display.timeMs
                } else {
                    SystemClock.elapsedRealtime() - display.timeMs
                }
                tile.setChronometerCountDown(R.id.widget_habit_chrono, isCountDown)
                tile.setChronometer(R.id.widget_habit_chrono, base, null, true)
                tile.setTextViewTextSize(
                    R.id.widget_habit_chrono, TypedValue.COMPLEX_UNIT_DIP, badge.textSize
                )
                tile.setViewPadding(
                    R.id.widget_habit_chrono,
                    0,
                    (badge.topInset * density).toInt(),
                    (badge.rightInset * density).toInt(),
                    0
                )
                tile.setViewVisibility(R.id.widget_habit_chrono, View.VISIBLE)
            } else {
                tile.setChronometer(R.id.widget_habit_chrono, SystemClock.elapsedRealtime(), null, false)
                tile.setViewVisibility(R.id.widget_habit_chrono, View.GONE)
            }

            tile.setContentDescription(R.id.widget_habit_item, habit.title)
            // Every habit can be tapped, also one that is not scheduled for the displayed
            // day (it is only drawn faded). One PendingIntent per habit: they are told
            // apart by the data Uri (extras do not count), so each tile keeps its own id.
            val tapIntent = Intent(context, HabitWidgetActionReceiver::class.java).apply {
                action = HabitWidgetActionReceiver.ACTION_HABIT_CLICK
                data = Uri.parse(TILE_URI_PREFIX + Uri.encode(habit.id))
                putExtra(HabitWidgetActionReceiver.EXTRA_HABIT_ID, habit.id)
            }
            val tapPendingIntent = PendingIntent.getBroadcast(
                context, REQ_TILE, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            tile.setOnClickPendingIntent(R.id.widget_habit_item, tapPendingIntent)
        }

        private fun wakeAlarmPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, HabitWidgetActionReceiver::class.java).apply {
                action = HabitWidgetActionReceiver.ACTION_STALE_ALARM
            }
            return PendingIntent.getBroadcast(
                context, REQ_WAKE_ALARM, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * One inexact alarm redraws the widget just after midnight, so it says
         * "outdated" even if nobody touches it. Needs no permission; being a little late
         * does not matter. (Anything missed is caught by the next tap, resize or hourly
         * system update.)
         */
        private fun scheduleStaleAlarm(context: Context, validUntil: Long?) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return
            val pendingIntent = wakeAlarmPendingIntent(context)
            val target = validUntil
                ?.takeIf { it > System.currentTimeMillis() }
                ?.plus(STALE_ALARM_SLACK_MS)
            if (target == null) {
                alarmManager.cancel(pendingIntent)
                return
            }
            try {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC, target, pendingIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to schedule habit widget wake-up", e)
            }
        }

        private fun cancelWakeAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return
            alarmManager.cancel(wakeAlarmPendingIntent(context))
        }
    }
}
