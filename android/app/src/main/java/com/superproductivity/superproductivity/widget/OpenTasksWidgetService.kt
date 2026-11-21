package com.superproductivity.superproductivity.widget

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.superproductivity.superproductivity.App
import com.superproductivity.superproductivity.R

class OpenTasksWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return OpenTasksRemoteViewsFactory(applicationContext)
    }
}

/** Rows of the open tasks widget; reuses the Today widget's row layout. */
private class OpenTasksRemoteViewsFactory(
    private val context: Context
) : RemoteViewsService.RemoteViewsFactory {

    private var tasks: List<WidgetTask> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        tasks = try {
            val json = (context.applicationContext as App).keyValStore
                .get(OpenTasksWidgetProvider.KEYVAL_KEY, "{}")
            // The pending done taps are overlaid, then dropped: a task the user just
            // ticked disappears at once, and done tasks are never listed.
            WidgetData.parse(json, WidgetDoneQueue.peek(context))
                .filter { !it.isDone }
                .take(MAX_TASKS)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse open tasks widget data", e)
            emptyList()
        }
    }

    override fun onDestroy() {
        tasks = emptyList()
    }

    override fun getCount(): Int = tasks.size

    override fun getViewAt(position: Int): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_task_row)
        if (position >= tasks.size) {
            return rv
        }

        val task = tasks[position]
        rv.setTextViewText(R.id.widget_task_title, task.title)

        // Project dot: tinted with the project colour, hidden for project-less tasks
        val color = task.projectColor?.let {
            try {
                Color.parseColor(it)
            } catch (e: Exception) {
                null
            }
        }
        if (color != null) {
            rv.setViewVisibility(R.id.widget_project_dot, View.VISIBLE)
            rv.setInt(R.id.widget_project_dot, "setColorFilter", color)
        } else {
            rv.setViewVisibility(R.id.widget_project_dot, View.GONE)
        }

        // The checkbox marks the task done; anywhere else on the row opens the app.
        rv.setOnClickFillInIntent(
            R.id.widget_done_checkbox,
            Intent().putExtra(TaskListWidgetProvider.EXTRA_TASK_ID, task.id)
        )
        rv.setOnClickFillInIntent(
            R.id.widget_task_row,
            Intent().putExtra(TaskListWidgetProvider.EXTRA_OPEN_APP, true)
        )
        return rv
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = false

    companion object {
        private const val TAG = "OpenTasksWidget"
        private const val MAX_TASKS = 50
    }
}
