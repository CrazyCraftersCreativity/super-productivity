import { createSelector } from '@ngrx/store';
import { selectEnabledSimpleCounters } from '../../simple-counter/store/simple-counter.reducer';
import {
  SimpleCounter,
  SimpleCounterType,
} from '../../simple-counter/simple-counter.model';
import {
  selectStartOfNextDayDiffMs,
  selectTodayStr,
} from '../../../root-store/app-state/app-state.selectors';
import {
  AndroidHabitWidgetData,
  AndroidHabitWidgetKind,
  AndroidWidgetHabit,
} from '../android-habit-widget.model';
import { getWidgetValidUntil } from './android-widget.selectors';

const MINUTE_MS = 60 * 1000;

/**
 * Same rule as HabitTrackerComponent.isSimpleCompletion: a habit tracked as a streak
 * whose goal is a single click is a yes/no habit.
 */
export const isHabitSimpleCompletion = (counter: SimpleCounter): boolean =>
  counter.type === SimpleCounterType.ClickCounter &&
  !!counter.isTrackStreaks &&
  (!counter.streakMinValue || counter.streakMinValue === 1);

export const getHabitWidgetKind = (counter: SimpleCounter): AndroidHabitWidgetKind => {
  if (counter.type === SimpleCounterType.StopWatch) {
    return 'time';
  }
  return isHabitSimpleCompletion(counter) ? 'bool' : 'count';
};

/** Same rule as HabitTrackerComponent.isDayEnabled, for the weekday of `dayStr`. */
export const isHabitDayEnabled = (counter: SimpleCounter, dayStr: string): boolean => {
  if (!counter.isTrackStreaks || counter.streakMode === 'weekly-frequency') {
    return true;
  }
  if (!counter.streakWeekDays) {
    return true;
  }
  const [year, month, day] = dayStr.split('-').map(Number);
  return !!counter.streakWeekDays[new Date(year, month - 1, day).getDay()];
};

/**
 * A running stopwatch changes its value every tick. Whole minutes are all the widget
 * shows, so it only gets a new snapshot once a minute — except when the goal is
 * reached, which must show up at the exact moment.
 */
const quantizeTimeMs = (rawMs: number, goalMs: number): number => {
  const wholeMinutesMs = Math.floor(rawMs / MINUTE_MS) * MINUTE_MS;
  return rawMs >= goalMs ? Math.max(goalMs, wholeMinutesMs) : wholeMinutesMs;
};

/**
 * Projects the enabled habits into the exact `widget_data_habits` blob shape (minus
 * the icons, which need the DOM and are added by HabitWidgetDataService). Lists the
 * same habits as the app's habit page: every enabled counter. Pure in its inputs
 * (no Date.now()), like selectAndroidWidgetData.
 */
export const selectAndroidHabitWidgetData = createSelector(
  selectEnabledSimpleCounters,
  selectTodayStr,
  selectStartOfNextDayDiffMs,
  (counters, dayStr, startOfNextDayDiffMs): AndroidHabitWidgetData => ({
    v: 1,
    dayStr,
    validUntil: getWidgetValidUntil(dayStr, startOfNextDayDiffMs),
    habits: counters.map((counter): AndroidWidgetHabit => {
      const kind = getHabitWidgetKind(counter);
      const goal = counter.streakMinValue || 1;
      const rawCount = counter.countOnDay?.[dayStr] ?? 0;
      return {
        id: counter.id,
        title: counter.title,
        kind,
        icon: counter.icon || null,
        count: kind === 'time' ? quantizeTimeMs(rawCount, goal) : rawCount,
        goal,
        dayEnabled: isHabitDayEnabled(counter, dayStr),
        isOn: kind === 'time' && !!counter.isOn,
      };
    }),
  }),
);
