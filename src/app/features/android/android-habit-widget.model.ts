import type { AndroidInterface } from './android-interface';

/** KeyValStore key under which the habits snapshot is stored for the native widget. */
export const ANDROID_HABIT_WIDGET_DATA_KEY = 'widget_data_habits';

/**
 * How a habit is operated on the widget:
 *  - `bool`: a streak habit with a goal of one click (tap toggles done / not done)
 *  - `count`: any other click or countdown counter (tap adds one)
 *  - `time`: a stopwatch (tap starts / stops it)
 *  - `countdown`: a repeated countdown (tap starts / pauses it; each time it runs out
 *    the count goes up by one)
 */
export type AndroidHabitWidgetKind = 'bool' | 'count' | 'time' | 'countdown';

export interface AndroidWidgetHabit {
  id: string;
  title: string;
  kind: AndroidHabitWidgetKind;
  /** Material Symbols ligature name, a `:custom-svg` name, or null (-> first letter) */
  icon: string | null;
  /** value on the snapshot's day: a count, or milliseconds for `time` habits */
  count: number;
  /** value at which the habit counts as done (always >= 1) */
  goal: number;
  /** false if the habit is not scheduled for that weekday (taps are ignored) */
  dayEnabled: boolean;
  /** `time` and `countdown` habits only: the timer is running inside the app */
  isOn: boolean;
  /** `countdown` habits only: length of one countdown in ms (0 otherwise) */
  countdownMs: number;
}

/** Pure projection of app state (see selectAndroidHabitWidgetData). */
export interface AndroidHabitWidgetData {
  v: 1;
  /** logical day (YYYY-MM-DD) this snapshot describes */
  dayStr: string;
  /** instant at which this snapshot stops describing "today" */
  validUntil: number;
  habits: AndroidWidgetHabit[];
}

/**
 * The blob read natively by HabitWidgetData.kt: the projection plus the habit icons,
 * rendered to PNG with the app's own icon font. Keep both ends in sync and bump `v`
 * on breaking changes.
 */
export interface AndroidHabitWidgetSnapshot extends AndroidHabitWidgetData {
  /** icon name -> base64 PNG (white glyph on transparent background) */
  icons: { [iconName: string]: string };
}

export type HabitWidgetEventKind = 'count' | 'bool' | 'time' | 'stopApp';

/** One tap made on the widget, queued natively (HabitWidgetQueue.kt) until applied. */
export interface HabitWidgetEvent {
  id: string;
  habitId: string;
  kind: HabitWidgetEventKind;
  /** when the tap happened (epoch ms); the day it is booked on derives from this */
  at: number;
  /** `bool`: 1 = set done, 0 = set not done */
  value: number;
  /** `time`: the stopwatch run that is added to the habit's time */
  startAt: number;
  endAt: number;
}

/** Native bridge methods of the habits widget (JavaScriptInterface.kt). Optional: older APKs lack them. */
export interface HabitWidgetBridge {
  // Non-destructive read: JSON array of events, or null if it could not be read
  getHabitWidgetEvents?(): string | null;
  // Delete events (JSON array of ids) once they are applied. Returns false on failure.
  acknowledgeHabitWidgetEvents?(idsJson: string): boolean;
  // Re-render the habits widget from the current widget_data_habits snapshot
  updateHabitWidget?(): void;
  // Data is fresh: lets the app go back home if the widget's refresh button opened it
  habitWidgetRefreshDone?(): void;
}

export type AndroidInterfaceWithHabitWidget = AndroidInterface & HabitWidgetBridge;
