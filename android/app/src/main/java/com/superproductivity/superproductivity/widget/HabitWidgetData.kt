package com.superproductivity.superproductivity.widget

import org.json.JSONObject
import java.util.Locale

/**
 * How a habit is operated: a yes/no check, a numeric counter, a stopwatch, or a
 * repeated countdown (each time it runs out, the count goes up by one).
 */
enum class HabitKind {
    BOOL, COUNT, TIME, COUNTDOWN;

    companion object {
        fun fromWire(value: String?): HabitKind? = when (value) {
            "bool" -> BOOL
            "count" -> COUNT
            "time" -> TIME
            "countdown" -> COUNTDOWN
            else -> null
        }
    }
}

/**
 * One habit as described by the snapshot Angular wrote.
 *
 * [count] is the value for the snapshot's day (a count, or milliseconds for TIME
 * habits) and [goal] the value at which the habit counts as done today.
 * [isOn] is only meaningful for TIME and COUNTDOWN habits: the timer is running inside
 * the app. [countdownMs] is the length of one countdown (COUNTDOWN habits only).
 */
data class WidgetHabit(
    val id: String,
    val title: String,
    val kind: HabitKind,
    val icon: String?,
    val count: Long,
    val goal: Long,
    val dayEnabled: Boolean,
    val isOn: Boolean,
    val countdownMs: Long
)

/**
 * The parsed `widget_data_habits` blob. [validUntil] is the instant the snapshot's day
 * ends (null = unknown, e.g. a blob from before the stamp existed); [icons] maps a
 * Material Symbols name to a base64 PNG (white glyph, transparent background) that
 * Angular rendered with the app's own icon font.
 */
data class HabitSnapshot(
    val dayStr: String?,
    val validUntil: Long?,
    val habits: List<WidgetHabit>,
    val icons: Map<String, String>
)

enum class HabitEventKind(val wire: String) {
    COUNT("count"),
    BOOL("bool"),
    TIME("time"),
    STOP_APP("stopApp");

    companion object {
        fun fromWire(value: String?): HabitEventKind? = values().firstOrNull { it.wire == value }
    }
}

/**
 * One tap made on the widget, waiting for Angular to apply it. [at] is when the tap
 * happened — Angular derives the day from it, so a tap made yesterday is booked on
 * yesterday even if the app only opens today.
 *
 * COUNT: add one at [at]. BOOL: set the day of [at] to done ([value] = 1) or not (0).
 * TIME: a finished stopwatch run from [startAt] to [endAt]. STOP_APP: stop a stopwatch
 * that is running inside the app.
 */
data class HabitEvent(
    val id: String,
    val habitId: String,
    val kind: HabitEventKind,
    val at: Long,
    val value: Int = 0,
    val startAt: Long = 0L,
    val endAt: Long = 0L
)

/**
 * A countdown started from the widget, remembered natively (the app may be closed).
 * Running: it runs out at [endsAt] (epoch ms). Paused: [remainingMs] is what is left.
 */
data class CountdownState(
    val isRunning: Boolean,
    val endsAt: Long,
    val remainingMs: Long
)

/**
 * What one habit looks like right now: snapshot plus pending taps, as of "now".
 *
 * [timeMs] depends on the kind: a running stopwatch = everything tracked today
 * including the current run; a running or paused countdown = time left; otherwise 0.
 */
data class HabitDisplay(
    val habit: WidgetHabit,
    val count: Long,
    val isDone: Boolean,
    val isRunning: Boolean,
    val isPaused: Boolean,
    val timeMs: Long
)

/**
 * Native end of the `widget_data_habits` KeyValStore contract. The writer is Angular's
 * HabitWidgetDataService; the blob shape is AndroidHabitWidgetSnapshot in
 * src/app/features/android/android-habit-widget.model.ts — keep both ends in sync and
 * bump `v` on breaking changes.
 *
 * Pure (no Context) so the day-rollover rules below can be unit-tested.
 */
object HabitWidgetData {
    const val KEYVAL_KEY = "widget_data_habits"
    private const val SUPPORTED_VERSION = 1

    val EMPTY = HabitSnapshot(null, null, emptyList(), emptyMap())

    fun parseSnapshot(json: String): HabitSnapshot {
        return try {
            val root = JSONObject(json)
            if (root.optInt("v", -1) != SUPPORTED_VERSION) {
                return EMPTY
            }
            val habits = mutableListOf<WidgetHabit>()
            val arr = root.optJSONArray("habits")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val h = arr.getJSONObject(i)
                    val kind = HabitKind.fromWire(h.optString("kind")) ?: continue
                    habits.add(
                        WidgetHabit(
                            id = h.getString("id"),
                            title = h.optString("title"),
                            kind = kind,
                            // isNull guard: optString maps JSON null to the string "null"
                            icon = if (h.isNull("icon")) null
                            else h.optString("icon").takeIf { it.isNotEmpty() },
                            count = h.optLong("count", 0L),
                            goal = h.optLong("goal", 1L).coerceAtLeast(1L),
                            dayEnabled = h.optBoolean("dayEnabled", true),
                            isOn = h.optBoolean("isOn", false),
                            countdownMs = h.optLong("countdownMs", 0L).coerceAtLeast(0L)
                        )
                    )
                }
            }
            val icons = mutableMapOf<String, String>()
            val iconsObj = root.optJSONObject("icons")
            if (iconsObj != null) {
                val keys = iconsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = iconsObj.optString(key)
                    if (value.isNotEmpty()) {
                        icons[key] = value
                    }
                }
            }
            HabitSnapshot(
                dayStr = if (root.isNull("dayStr")) null
                else root.optString("dayStr").takeIf { it.isNotEmpty() },
                // takeIf: absent and JSON-null both read as 0L, which would look like
                // "expired in 1970". Missing must mean unknown.
                validUntil = root.optLong("validUntil", 0L).takeIf { it > 0L },
                habits = habits,
                icons = icons
            )
        } catch (e: Exception) {
            EMPTY
        }
    }

    /** The snapshot's day is over. Unknown stamp = not stale (rows are still the best we have). */
    fun isStale(validUntil: Long?, nowMs: Long): Boolean =
        validUntil != null && nowMs >= validUntil

    /**
     * Applies pending taps on top of the snapshot.
     *
     * Fresh snapshot: its counts plus every pending tap (all of them happened after the
     * snapshot was written and before its day ended).
     * Stale snapshot (past midnight): the new day starts from zero — yesterday's counts
     * are not today's — and only taps made since the day ended count. Taps from before
     * midnight stay in the queue untouched; Angular books them on their own day.
     *
     * A countdown that has already run out but is not booked yet counts as one more.
     */
    fun display(
        snapshot: HabitSnapshot,
        events: List<HabitEvent>,
        runningTimers: Map<String, Long>,
        countdowns: Map<String, CountdownState>,
        nowMs: Long
    ): List<HabitDisplay> {
        val boundary = snapshot.validUntil
        val stale = isStale(boundary, nowMs)
        val sortedEvents = events.sortedBy { it.at }
        return snapshot.habits.map { habit ->
            var count = if (stale) 0L else habit.count
            var appStopped = false
            for (event in sortedEvents) {
                if (event.habitId != habit.id) continue
                when (event.kind) {
                    HabitEventKind.COUNT ->
                        if (isInWindow(event.at, boundary, stale)) count += 1L

                    HabitEventKind.BOOL ->
                        if (isInWindow(event.at, boundary, stale)) {
                            count = if (event.value == 1) maxOf(count, 1L) else 0L
                        }

                    HabitEventKind.TIME -> count += timeInWindow(event, boundary, stale)

                    HabitEventKind.STOP_APP ->
                        if (isInWindow(event.at, boundary, stale)) appStopped = true
                }
            }

            var isRunning = false
            var isPaused = false
            var timeMs = 0L
            when (habit.kind) {
                HabitKind.TIME -> {
                    val startedAt = runningTimers[habit.id]
                    if (startedAt != null) {
                        // Only the part of the run that falls on the displayed day.
                        val from =
                            if (stale && boundary != null) maxOf(startedAt, boundary) else startedAt
                        isRunning = true
                        timeMs = count + maxOf(0L, nowMs - from)
                    } else if (!stale && habit.isOn && !appStopped) {
                        // Running inside the app: its start is unknown here, so the
                        // clock starts from the tracked time of the last snapshot.
                        isRunning = true
                        timeMs = count
                    }
                }

                HabitKind.COUNTDOWN -> {
                    val state = countdowns[habit.id]
                    if (state != null) {
                        if (state.isRunning) {
                            if (state.endsAt <= nowMs) {
                                // Ran out, not booked yet: one more for the day it ran out on.
                                if (isInWindow(state.endsAt, boundary, stale)) count += 1L
                            } else {
                                isRunning = true
                                timeMs = state.endsAt - nowMs
                            }
                        } else if (state.remainingMs > 0L) {
                            isPaused = true
                            timeMs = state.remainingMs
                        }
                    } else if (!stale && habit.isOn && !appStopped) {
                        // Running inside the app: remaining time unknown here.
                        isRunning = true
                    }
                }

                HabitKind.BOOL, HabitKind.COUNT -> Unit
            }
            HabitDisplay(habit, count, count >= habit.goal, isRunning, isPaused, timeMs)
        }
    }

    /** True when a live on-screen clock (Chronometer) shows this habit's time. */
    fun usesChronometer(display: HabitDisplay): Boolean = when (display.habit.kind) {
        HabitKind.TIME -> display.isRunning
        HabitKind.COUNTDOWN -> display.isRunning && display.timeMs > 0L
        else -> false
    }

    private fun isInWindow(at: Long, boundary: Long?, stale: Boolean): Boolean {
        if (boundary == null) return true
        return if (stale) at >= boundary else at < boundary
    }

    /** The part of a finished stopwatch run that falls on the displayed day. */
    private fun timeInWindow(event: HabitEvent, boundary: Long?, stale: Boolean): Long {
        if (event.endAt - event.startAt <= 0L) return 0L
        if (boundary == null) return event.endAt - event.startAt
        return if (stale) {
            maxOf(0L, event.endAt - maxOf(event.startAt, boundary))
        } else {
            maxOf(0L, minOf(event.endAt, boundary) - event.startAt)
        }
    }

    /** Small label in the tile corner: "3", "3/8", "12m", "/3", or empty when there is nothing to say. */
    fun badgeText(display: HabitDisplay): String {
        val habit = display.habit
        return when (habit.kind) {
            HabitKind.BOOL -> ""
            HabitKind.COUNT -> when {
                habit.goal > 1L -> "${display.count}/${habit.goal}"
                display.count > 0L -> display.count.toString()
                else -> ""
            }

            // The count itself is the big number in the middle; only the goal is added.
            HabitKind.COUNTDOWN -> if (habit.goal > 1L) "/${habit.goal}" else ""

            HabitKind.TIME ->
                if (display.isRunning) "" else formatDuration(display.count)
        }
    }

    /** "m:ss" (or "h:mm:ss") for a time left, rounded up to whole seconds. */
    fun formatClock(ms: Long): String {
        val totalSeconds = (maxOf(0L, ms) + 999L) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0L) return ""
        val minutes = (ms + 30_000L) / 60_000L
        if (minutes < 1L) return "<1m"
        if (minutes < 60L) return "${minutes}m"
        val hours = minutes / 60L
        val rest = minutes % 60L
        return if (rest > 0L) "${hours}h${rest}m" else "${hours}h"
    }

    /** First letter of the title as a whole code point (safe for emoji), uppercased. */
    fun initialOf(title: String): String {
        val t = title.trim()
        if (t.isEmpty()) return "?"
        val codePoint = t.codePointAt(0)
        return String(Character.toChars(codePoint)).uppercase()
    }
}
