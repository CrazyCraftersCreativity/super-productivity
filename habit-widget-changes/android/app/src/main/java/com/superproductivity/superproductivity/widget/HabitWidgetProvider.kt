package com.superproductivity.superproductivity.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.superproductivity.superproductivity.App
import com.superproductivity.superproductivity.CapacitorMainActivity
import com.superproductivity.superproductivity.R

/**
 * Home screen widget showing one tile per habit, read from the `widget_data_habits`
 * snapshot pushed by Angular. Tiles keep a fixed size: a bigger widget shows more of
 * them, a smaller one fewer. Taps are not handled here — they go to
 * [HabitWidgetActionReceiver], which queues them in [HabitWidgetQueue] for Angular.
 *
 * The widget is never "live": it shows the last snapshot plus the taps queued since.
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

    /** The user resized the widget: the banner only fits above a certain height. */
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
        // Last habits widget removed: no more midnight redraw needed.
        cancelStaleAlarm(context)
    }

    companion object {
        private const val TAG = "HabitWidget"

        // Distinct request codes: PendingIntents are matched without their extras, so
        // reusing the task widget's code 0 for the same activity would overwrite it.
        private const val REQ_REFRESH = 7301
        private const val REQ_OPEN_APP = 7302
        private const val REQ_CLICK_TEMPLATE = 7303
        private const val REQ_STALE_ALARM = 7304

        /** Below this widget height (dp) there is no room for the text banner. */
        private const val BANNER_MIN_HEIGHT_DP = 72

        /** Redraw this long after midnight, so the new day has certainly begun. */
        private const val STALE_ALARM_SLACK_MS = 1_000L

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
            val snapshot = loadSnapshot(context)
            val isStale = HabitWidgetData.isStale(snapshot.validUntil, System.currentTimeMillis())
            for (appWidgetId in appWidgetIds) {
                updateWidget(context, appWidgetManager, appWidgetId, isStale)
            }
            // setRemoteAdapter alone does not re-run the factory's onDataSetChanged()
            // when the adapter intent is unchanged (it always is: same id, same Uri).
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.widget_habit_grid)
            scheduleStaleAlarm(context, snapshot.validUntil)
        }

        private fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            isStale: Boolean
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_habit_list)

            // Habit tiles
            val serviceIntent = Intent(context, HabitWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widget_habit_grid, serviceIntent)
            views.setEmptyView(R.id.widget_habit_grid, R.id.widget_habit_empty)

            // One template for all tile taps. Explicit component, and the receiver is
            // not exported, so no other app can inject habit taps. MUTABLE is required
            // for the per-tile fill-in extras.
            val clickIntent = Intent(context, HabitWidgetActionReceiver::class.java).apply {
                action = HabitWidgetActionReceiver.ACTION_HABIT_CLICK
            }
            val clickPendingIntent = PendingIntent.getBroadcast(
                context, REQ_CLICK_TEMPLATE, clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            views.setPendingIntentTemplate(R.id.widget_habit_grid, clickPendingIntent)

            // Refresh button: opens the app with a flag; the app closes itself again
            // after it has pushed fresh data (see HabitWidgetRefreshGate).
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

            // "No habits" text: plain open-app tap
            val openAppIntent = Intent(context, CapacitorMainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                context, REQ_OPEN_APP, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_habit_empty, openAppPendingIntent)

            // Outdated statement. The refresh button turns amber at every size; the
            // text banner only appears when the widget is tall enough to hold it.
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val heightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
            val showBanner = isStale && heightDp >= BANNER_MIN_HEIGHT_DP
            views.setViewVisibility(
                R.id.widget_habit_banner,
                if (showBanner) View.VISIBLE else View.GONE
            )
            views.setImageViewResource(
                R.id.widget_habit_refresh_bg,
                if (isStale) R.drawable.widget_habit_circle_outdated
                else R.drawable.widget_habit_circle_open
            )
            views.setInt(
                R.id.widget_habit_refresh_icon,
                "setColorFilter",
                if (isStale) Color.WHITE else context.getColor(R.color.widget_ink)
            )

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun staleAlarmPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, HabitWidgetActionReceiver::class.java).apply {
                action = HabitWidgetActionReceiver.ACTION_STALE_ALARM
            }
            return PendingIntent.getBroadcast(
                context, REQ_STALE_ALARM, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * Wakes the widget just after the snapshot's day ends so it can say "outdated"
         * without anyone touching it. Inexact on purpose (no exact-alarm permission,
         * and a few minutes' delay is harmless); a missed alarm is still caught by
         * the next tap, resize or hourly system update.
         */
        private fun scheduleStaleAlarm(context: Context, validUntil: Long?) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return
            val pendingIntent = staleAlarmPendingIntent(context)
            if (validUntil == null || validUntil <= System.currentTimeMillis()) {
                alarmManager.cancel(pendingIntent)
                return
            }
            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC,
                    validUntil + STALE_ALARM_SLACK_MS,
                    pendingIntent
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to schedule habit widget midnight redraw", e)
            }
        }

        private fun cancelStaleAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return
            alarmManager.cancel(staleAlarmPendingIntent(context))
        }
    }
}
