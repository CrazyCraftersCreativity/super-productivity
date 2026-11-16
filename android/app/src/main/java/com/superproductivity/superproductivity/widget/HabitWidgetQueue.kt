package com.superproductivity.superproductivity.widget

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * SharedPreferences-backed store for everything the habits widget has to remember
 * until the app runs again:
 *  - [HabitEvent]s: taps made on the widget. Reads never delete; Angular removes an
 *    event with [acknowledge] only after it has applied it, so a crash in between
 *    redelivers instead of losing the tap.
 *  - running stopwatches: habit id -> start instant, for stopwatches started from the
 *    widget while the app is not running. Stopping one turns it into a TIME event.
 *  - countdowns started from the widget: habit id -> running (end instant) or paused
 *    (time left). When one runs out it is turned into a COUNT event at the moment it
 *    ran out, so Angular books it on the right day — see [settleExpiredCountdowns].
 *
 * Writes use commit() (not apply()): they run in short-lived broadcasts, and the
 * process may be killed right afterwards.
 */
object HabitWidgetQueue {
    private const val PREFS_NAME = "SuperProductivityHabitWidget"
    private const val KEY_EVENTS = "HABIT_EVENTS"
    private const val KEY_TIMERS = "HABIT_RUNNING_TIMERS"
    private const val KEY_COUNTDOWNS = "HABIT_COUNTDOWNS"
    private const val MAX_EVENTS = 2000

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readArray(prefs: SharedPreferences): JSONArray {
        val raw = prefs.getString(KEY_EVENTS, null) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun toJson(event: HabitEvent): JSONObject = JSONObject()
        .put("id", event.id)
        .put("habitId", event.habitId)
        .put("kind", event.kind.wire)
        .put("at", event.at)
        .put("value", event.value)
        .put("startAt", event.startAt)
        .put("endAt", event.endAt)

    private fun fromJson(json: JSONObject?): HabitEvent? {
        if (json == null) return null
        val id = json.optString("id")
        val habitId = json.optString("habitId")
        val kind = HabitEventKind.fromWire(json.optString("kind"))
        if (id.isEmpty() || habitId.isEmpty() || kind == null) return null
        return HabitEvent(
            id = id,
            habitId = habitId,
            kind = kind,
            at = json.optLong("at", 0L),
            value = json.optInt("value", 0),
            startAt = json.optLong("startAt", 0L),
            endAt = json.optLong("endAt", 0L)
        )
    }

    @Synchronized
    fun addEvent(context: Context, event: HabitEvent) {
        val prefs = prefs(context)
        val array = readArray(prefs)
        array.put(toJson(event))
        while (array.length() > MAX_EVENTS) {
            array.remove(0)
        }
        prefs.edit().putString(KEY_EVENTS, array.toString()).commit()
    }

    /** Non-destructive read of all valid events, oldest first. */
    @Synchronized
    fun peekEvents(context: Context): List<HabitEvent> {
        val array = readArray(prefs(context))
        val result = mutableListOf<HabitEvent>()
        for (i in 0 until array.length()) {
            fromJson(array.optJSONObject(i))?.let { result.add(it) }
        }
        return result.sortedBy { it.at }
    }

    /** Same as [peekEvents], as the JSON array string handed to Angular. */
    @Synchronized
    fun eventsJson(context: Context): String {
        // A countdown that ran out while nobody was looking becomes an event first, so
        // the app applies it together with the taps.
        settleExpiredCountdowns(context, System.currentTimeMillis())
        val array = JSONArray()
        for (event in peekEvents(context)) {
            array.put(toJson(event))
        }
        return array.toString()
    }

    /** Removes the events with these ids. Unknown ids are ignored. */
    @Synchronized
    fun acknowledge(context: Context, ids: Set<String>) {
        if (ids.isEmpty()) return
        val prefs = prefs(context)
        val old = readArray(prefs)
        val kept = JSONArray()
        for (i in 0 until old.length()) {
            val entry = old.optJSONObject(i) ?: continue
            if (!ids.contains(entry.optString("id"))) {
                kept.put(entry)
            }
        }
        prefs.edit().putString(KEY_EVENTS, kept.toString()).commit()
    }

    private fun readTimers(prefs: SharedPreferences): JSONObject {
        val raw = prefs.getString(KEY_TIMERS, null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    @Synchronized
    fun runningTimers(context: Context): Map<String, Long> {
        val timers = readTimers(prefs(context))
        val result = mutableMapOf<String, Long>()
        val keys = timers.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val startedAt = timers.optLong(key, 0L)
            if (startedAt > 0L) {
                result[key] = startedAt
            }
        }
        return result
    }

    @Synchronized
    fun startTimer(context: Context, habitId: String, startedAt: Long) {
        val prefs = prefs(context)
        val timers = readTimers(prefs)
        timers.put(habitId, startedAt)
        prefs.edit().putString(KEY_TIMERS, timers.toString()).commit()
    }

    /** Stops the timer and returns when it was started, or null if none was running. */
    @Synchronized
    fun stopTimer(context: Context, habitId: String): Long? {
        val prefs = prefs(context)
        val timers = readTimers(prefs)
        if (!timers.has(habitId)) return null
        val startedAt = timers.optLong(habitId, 0L)
        timers.remove(habitId)
        prefs.edit().putString(KEY_TIMERS, timers.toString()).commit()
        return if (startedAt > 0L) startedAt else null
    }

    private fun readCountdowns(prefs: SharedPreferences): JSONObject {
        val raw = prefs.getString(KEY_COUNTDOWNS, null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    @Synchronized
    fun countdowns(context: Context): Map<String, CountdownState> {
        val states = readCountdowns(prefs(context))
        val result = mutableMapOf<String, CountdownState>()
        val keys = states.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val entry = states.optJSONObject(key) ?: continue
            result[key] = CountdownState(
                isRunning = entry.optBoolean("running", false),
                endsAt = entry.optLong("endsAt", 0L),
                remainingMs = entry.optLong("remaining", 0L)
            )
        }
        return result
    }

    /** Starts (or resumes) a countdown that runs out at [endsAt]. */
    @Synchronized
    fun startCountdown(context: Context, habitId: String, endsAt: Long) {
        val prefs = prefs(context)
        val states = readCountdowns(prefs)
        states.put(
            habitId,
            JSONObject().put("running", true).put("endsAt", endsAt).put("remaining", 0L)
        )
        prefs.edit().putString(KEY_COUNTDOWNS, states.toString()).commit()
    }

    /** Pauses a countdown with [remainingMs] left. */
    @Synchronized
    fun pauseCountdown(context: Context, habitId: String, remainingMs: Long) {
        val prefs = prefs(context)
        val states = readCountdowns(prefs)
        states.put(
            habitId,
            JSONObject().put("running", false).put("endsAt", 0L).put("remaining", remainingMs)
        )
        prefs.edit().putString(KEY_COUNTDOWNS, states.toString()).commit()
    }

    /** The end instant of the soonest running countdown, or null if none is running. */
    @Synchronized
    fun nextCountdownEnd(context: Context): Long? {
        var next: Long? = null
        for (state in countdowns(context).values) {
            if (state.isRunning && state.endsAt > 0L) {
                next = if (next == null) state.endsAt else minOf(next, state.endsAt)
            }
        }
        return next
    }

    /**
     * Turns every running countdown whose end has passed into a COUNT event dated at
     * its end, and forgets it. Event and state change are written in ONE commit, so a
     * crash can neither lose the count nor book it twice. Returns true if anything
     * was settled.
     */
    @Synchronized
    fun settleExpiredCountdowns(context: Context, nowMs: Long): Boolean {
        val prefs = prefs(context)
        val states = readCountdowns(prefs)
        val expired = mutableListOf<Pair<String, Long>>()
        val keys = states.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val entry = states.optJSONObject(key) ?: continue
            val endsAt = entry.optLong("endsAt", 0L)
            if (entry.optBoolean("running", false) && endsAt in 1..nowMs) {
                expired.add(Pair(key, endsAt))
            }
        }
        if (expired.isEmpty()) {
            return false
        }
        val events = readArray(prefs)
        for ((habitId, endsAt) in expired) {
            events.put(
                toJson(
                    HabitEvent(
                        id = UUID.randomUUID().toString(),
                        habitId = habitId,
                        kind = HabitEventKind.COUNT,
                        at = endsAt
                    )
                )
            )
            states.remove(habitId)
        }
        while (events.length() > MAX_EVENTS) {
            events.remove(0)
        }
        prefs.edit()
            .putString(KEY_EVENTS, events.toString())
            .putString(KEY_COUNTDOWNS, states.toString())
            .commit()
        return true
    }
}
