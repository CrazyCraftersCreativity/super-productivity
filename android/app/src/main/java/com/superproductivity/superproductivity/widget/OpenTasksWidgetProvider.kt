package com.superproductivity.superproductivity.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.superproductivity.superproductivity.App
import com.superproductivity.superproductivity.CapacitorMainActivity
import com.superproductivity.superproductivity.R

/**
 * Home screen widget listing the open tasks that are not planned for any day and do
 * not repeat, from the `widget_data_open_tasks` snapshot pushed by Angular.
 *
 * Checkbox taps enqueue the task in [WidgetDoneQueue] — the same queue the Today widget
 * uses, so Angular's existing drain applies them — and the task disappears from the list
 * right away (done tasks are never shown). Like the Today widget it is not live: it shows
 * the last snapshot, and once that snapshot's day is over it says "Outdated" until the
 * refresh button (or any app launch) brings fresh data.
 */
class OpenTasksWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        updateAll(context, appWidgetManager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_CLICK) {
            return
        }
        // One PendingIntent template serves all rows; the fill-in extras (shared with
        // the Today widget's rows) tell which part of a row was tapped.
        val taskId = intent.getStringExtra(TaskListWidgetProvider.EXTRA_TASK_ID)
        when {
            taskId != null -> {
                Log.d(TAG, "Mark done from open tasks widget: taskId=$taskId")
                WidgetDoneQueue.setTarget(context, taskId, true)
                // Hide the task now, and let the Today widget show its checked box.
                refreshAll(context)
                TaskListWidgetProvider.refreshAll(context)
                // Contentless "drain now" signal for a live app; Angular pulls the
                // task ids from the queue itself.
                LocalBroadcastManager.getInstance(context)
                    .sendBroadcast(Intent(TaskListWidgetProvider.ACTION_WIDGET_DONE_DRAIN))
            }

            intent.getBooleanExtra(TaskListWidgetProvider.EXTRA_OPEN_APP, false) -> {
                try {
                    context.startActivity(
                        Intent(context, CapacitorMainActivity::class.java).apply {
                            flags =
                                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                    )
                } catch (e: Exception) {
                    // Background-activity-launch restrictions may block this; the
                    // header tap (direct activity PendingIntent) stays as the way in.
                    Log.w(TAG, "Failed to open app from open tasks widget row tap", e)
                }
            }
        }
    }

    companion object {
        private const val TAG = "OpenTasksWidget"
        const val KEYVAL_KEY = "widget_data_open_tasks"
        private const val ACTION_CLICK =
            "com.superproductivity.superproductivity.OPEN_TASKS_WIDGET_CLICK"

        // Distinct request codes (PendingIntents are matched without their extras).
        private const val REQ_CLICK = 7401
        private const val REQ_OPEN_APP = 7402
        private const val REQ_REFRESH = 7403

        private fun widgetIds(context: Context, appWidgetManager: AppWidgetManager): IntArray =
            appWidgetManager.getAppWidgetIds(
                ComponentName(context, OpenTasksWidgetProvider::class.java)
            )

        /** Redraws every placed open tasks widget. Called after Angular pushes and after taps. */
        fun refreshAll(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = widgetIds(context, appWidgetManager)
            if (ids.isEmpty()) {
                return
            }
            updateAll(context, appWidgetManager, ids)
        }

        /** Is the snapshot older than the logical day it was written on? Unknown = not stale. */
        private fun isStale(context: Context): Boolean {
            val json = try {
                (context.applicationContext as App).keyValStore.get(KEYVAL_KEY, "{}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read open tasks widget data", e)
                return false
            }
            return WidgetData.isSnapshotStale(WidgetData.parseMeta(json), System.currentTimeMillis())
        }

        private fun updateAll(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray
        ) {
            val isStale = isStale(context)
            for (appWidgetId in appWidgetIds) {
                try {
                    updateWidget(context, appWidgetManager, appWidgetId, isStale)
                } catch (e: Exception) {
                    // One broken widget must not stop the others.
                    Log.e(TAG, "Failed to update open tasks widget", e)
                }
            }
            // setRemoteAdapter alone does not make the factory re-read its data when the
            // adapter intent is unchanged (it always is), so ask for it explicitly.
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.widget_open_list)
        }

        private fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            isStale: Boolean
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_open_tasks)

            val serviceIntent = Intent(context, OpenTasksWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widget_open_list, serviceIntent)
            views.setEmptyView(R.id.widget_open_list, R.id.widget_open_empty)

            // Single template for all row clicks (explicit component, no manifest
            // intent-filter needed). MUTABLE is required for fill-ins.
            val clickPendingIntent = PendingIntent.getBroadcast(
                context, REQ_CLICK,
                Intent(context, OpenTasksWidgetProvider::class.java).apply { action = ACTION_CLICK },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            views.setPendingIntentTemplate(R.id.widget_open_list, clickPendingIntent)

            // Header title and empty text open the app.
            val openAppPendingIntent = PendingIntent.getActivity(
                context, REQ_OPEN_APP,
                Intent(context, CapacitorMainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_open_title, openAppPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_open_empty, openAppPendingIntent)

            // Refresh button: opens the app with a flag; the app closes itself again
            // after it has pushed fresh data (see HabitWidgetRefreshGate).
            val refreshPendingIntent = PendingIntent.getActivity(
                context, REQ_REFRESH,
                Intent(context, CapacitorMainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(HabitWidgetRefreshGate.EXTRA_REFRESH_AND_CLOSE, true)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_open_refresh, refreshPendingIntent)
            views.setContentDescription(
                R.id.widget_open_refresh,
                context.getString(R.string.widget_open_tasks_refresh)
            )

            // Outdated: the button turns amber and says so.
            views.setInt(
                R.id.widget_open_refresh,
                "setBackgroundResource",
                if (isStale) R.drawable.widget_habit_pill_outdated
                else R.drawable.widget_habit_pill_open
            )
            views.setInt(
                R.id.widget_open_refresh_icon,
                "setColorFilter",
                if (isStale) context.getColor(R.color.habit_on_outdated)
                else context.getColor(R.color.habit_accent)
            )
            views.setViewVisibility(
                R.id.widget_open_banner,
                if (isStale) View.VISIBLE else View.GONE
            )

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
