package com.superproductivity.superproductivity.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.util.UUID

/**
 * Receives the habits widget's tile taps and its midnight alarm. Kept out of
 * [HabitWidgetProvider] because the provider must be exported (the launcher sends it
 * APPWIDGET_UPDATE), while these actions must only be reachable through our own
 * PendingIntents — this receiver is not exported.
 *
 * A tap never changes the habit data itself (only Angular writes it): it appends an
 * event to [HabitWidgetQueue], redraws the widget so the tap shows immediately, and
 * pokes a running app to apply it. If the app is dead, the next app start drains the queue.
 */
class HabitWidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_HABIT_CLICK -> handleHabitClick(context, intent.getStringExtra(EXTRA_HABIT_ID))
            ACTION_STALE_ALARM -> {
                // Midnight passed, or a countdown ran out: book finished countdowns,
                // redraw, and (if the app is alive) ask it to apply them.
                val settled =
                    HabitWidgetQueue.settleExpiredCountdowns(context, System.currentTimeMillis())
                HabitWidgetProvider.refreshAll(context)
                if (settled) {
                    signalApp(context)
                }
            }
        }
    }

    /**
     * Contentless "drain now" signal for a live app. Reuses the task widget's signal:
     * its listener only triggers Angular's drain effects, which pull the queued events
     * themselves (single delivery path).
     */
    private fun signalApp(context: Context) {
        LocalBroadcastManager.getInstance(context)
            .sendBroadcast(Intent(TaskListWidgetProvider.ACTION_WIDGET_DONE_DRAIN))
    }

    private fun handleHabitClick(context: Context, habitId: String?) {
        if (habitId.isNullOrEmpty()) {
            return
        }
        val now = System.currentTimeMillis()
        // A countdown that has run out is booked first, so a tap starts a fresh one.
        HabitWidgetQueue.settleExpiredCountdowns(context, now)
        val snapshot = HabitWidgetProvider.loadSnapshot(context)
        val countdowns = HabitWidgetQueue.countdowns(context)
        // Decide from the state the user is looking at (snapshot + queued taps), so
        // repeated taps on a yes/no habit toggle back and forth.
        val display = HabitWidgetData.display(
            snapshot,
            HabitWidgetQueue.peekEvents(context),
            HabitWidgetQueue.runningTimers(context),
            countdowns,
            now
        ).firstOrNull { it.habit.id == habitId }
        // Habits that are not scheduled for today are drawn faded but can still be used:
        // they count like a finished habit that takes more entries.
        if (display == null) {
            return
        }

        try {
            when (display.habit.kind) {
                HabitKind.COUNT -> addEvent(context, habitId, HabitEventKind.COUNT, now)

                HabitKind.BOOL -> addEvent(
                    context, habitId, HabitEventKind.BOOL, now,
                    value = if (display.isDone) 0 else 1
                )

                HabitKind.TIME -> {
                    val startedAt = HabitWidgetQueue.stopTimer(context, habitId)
                    when {
                        // Stopwatch started from the widget: book the elapsed run.
                        startedAt != null -> addEvent(
                            context, habitId, HabitEventKind.TIME, now,
                            startAt = minOf(startedAt, now), endAt = now
                        )
                        // Stopwatch running inside the app: ask the app to stop it.
                        display.isRunning -> addEvent(context, habitId, HabitEventKind.STOP_APP, now)
                        // Nothing running: start a widget stopwatch.
                        else -> HabitWidgetQueue.startTimer(context, habitId, now)
                    }
                }

                HabitKind.COUNTDOWN -> {
                    val state = countdowns[habitId]
                    when {
                        // Running: pause, keeping what is left.
                        state != null && state.isRunning ->
                            HabitWidgetQueue.pauseCountdown(
                                context, habitId, maxOf(1L, state.endsAt - now)
                            )
                        // Paused: carry on from what was left.
                        state != null && state.remainingMs > 0L ->
                            HabitWidgetQueue.startCountdown(
                                context, habitId, now + state.remainingMs
                            )
                        // Running inside the app: ask the app to pause it.
                        display.isRunning -> addEvent(context, habitId, HabitEventKind.STOP_APP, now)
                        // Idle: start a full countdown. When it runs out it is booked as one count.
                        display.habit.countdownMs > 0L ->
                            HabitWidgetQueue.startCountdown(
                                context, habitId, now + display.habit.countdownMs
                            )
                        // No length configured: behave like a plain counter.
                        else -> addEvent(context, habitId, HabitEventKind.COUNT, now)
                    }
                }
            }
        } catch (e: Exception) {
            // Never log the habit title or id of user content beyond this message.
            Log.e(TAG, "Failed to record habit widget tap", e)
            return
        }

        HabitWidgetProvider.refreshAll(context)
        signalApp(context)
    }

    private fun addEvent(
        context: Context,
        habitId: String,
        kind: HabitEventKind,
        at: Long,
        value: Int = 0,
        startAt: Long = 0L,
        endAt: Long = 0L
    ) {
        HabitWidgetQueue.addEvent(
            context,
            HabitEvent(
                id = UUID.randomUUID().toString(),
                habitId = habitId,
                kind = kind,
                at = at,
                value = value,
                startAt = startAt,
                endAt = endAt
            )
        )
    }

    companion object {
        private const val TAG = "HabitWidget"
        const val ACTION_HABIT_CLICK = "com.superproductivity.superproductivity.HABIT_WIDGET_CLICK"
        const val ACTION_STALE_ALARM = "com.superproductivity.superproductivity.HABIT_WIDGET_STALE"
        const val EXTRA_HABIT_ID = "HABIT_WIDGET_HABIT_ID"
    }
}
