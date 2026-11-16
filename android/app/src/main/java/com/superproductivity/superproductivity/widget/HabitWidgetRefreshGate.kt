package com.superproductivity.superproductivity.widget

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

/**
 * Backs the habits widget's refresh button: the button opens the app (the only way to
 * run Angular), and this gate sends the app back to the home screen once Angular has
 * applied the queued taps and pushed a fresh snapshot.
 *
 * Armed by CapacitorMainActivity.handleIntent when the launching intent carries
 * [EXTRA_REFRESH_AND_CLOSE]; completed by Angular through the JavaScriptInterface
 * (habitWidgetRefreshDone). A timeout closes the app anyway, so a stuck startup can
 * never leave it open. A normal app launch never arms the gate, so completing it is a no-op.
 *
 * All state is touched on the main thread only.
 */
object HabitWidgetRefreshGate {
    const val EXTRA_REFRESH_AND_CLOSE = "HABIT_WIDGET_REFRESH_AND_CLOSE"

    /** Give up and close after this long if Angular never reports back. */
    private const val TIMEOUT_MS = 20_000L

    /** Grace after Angular reports back, so the last writes and widget redraw land. */
    private const val GRACE_MS = 2_500L

    private val handler = Handler(Looper.getMainLooper())
    private val closeRunnable = Runnable { closeNow() }
    private var activityRef: WeakReference<Activity>? = null
    private var isArmed = false

    /** Call from the activity's intent handling (main thread). */
    fun armIfRequested(activity: Activity, intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_REFRESH_AND_CLOSE, false)) return
        // Consume it so a replayed intent cannot arm the gate a second time.
        intent.removeExtra(EXTRA_REFRESH_AND_CLOSE)
        activityRef = WeakReference(activity)
        isArmed = true
        handler.removeCallbacks(closeRunnable)
        handler.postDelayed(closeRunnable, TIMEOUT_MS)
    }

    /** Called by Angular (any thread) once the widget data is fresh. */
    fun complete() {
        handler.post {
            if (!isArmed) return@post
            handler.removeCallbacks(closeRunnable)
            handler.postDelayed(closeRunnable, GRACE_MS)
        }
    }

    private fun closeNow() {
        if (!isArmed) return
        isArmed = false
        val activity = activityRef?.get()
        activityRef = null
        if (activity != null && !activity.isFinishing) {
            // Back to the home screen, app stays alive in recents.
            activity.moveTaskToBack(true)
        }
    }
}
