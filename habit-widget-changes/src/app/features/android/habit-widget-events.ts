import { Dictionary } from '@ngrx/entity';
import { SimpleCounter, SimpleCounterType } from '../simple-counter/simple-counter.model';
import { getDbDateStr } from '../../util/get-db-date-str';
import { HabitWidgetEvent, HabitWidgetEventKind } from './android-habit-widget.model';

const EVENT_KINDS: readonly HabitWidgetEventKind[] = [
  'count',
  'bool',
  'time',
  'stopApp',
];

/** A stopwatch run can not sensibly span more days than this; guards the split loop. */
const MAX_SPLIT_DAYS = 62;

export type HabitWidgetChange =
  | { type: 'setForDate'; id: string; date: string; newVal: number }
  | { type: 'setOff'; id: string };

export interface HabitWidgetPlan {
  changes: HabitWidgetChange[];
  /** every event that can be acknowledged: applied, already applied, or for a deleted habit */
  handledIds: string[];
}

/**
 * The logical day (YYYY-MM-DD) a moment belongs to. Same rule as
 * DateService.getLogicalTodayDate(): shift back by the start-of-next-day offset.
 */
export const getLogicalDayStr = (timestamp: number, startOfNextDayDiffMs: number): string =>
  getDbDateStr(new Date(timestamp - startOfNextDayDiffMs));

/**
 * Splits a time span at logical day boundaries, so a stopwatch that ran across
 * midnight is booked on both days. Uses local-date arithmetic (like getWidgetValidUntil)
 * so DST changes do not shift the boundary.
 */
export const splitIntoLogicalDays = (
  startAt: number,
  endAt: number,
  startOfNextDayDiffMs: number,
): { dayStr: string; durationMs: number }[] => {
  const result: { dayStr: string; durationMs: number }[] = [];
  let cursor = startAt;
  for (let i = 0; i < MAX_SPLIT_DAYS && cursor < endAt; i++) {
    const shifted = new Date(cursor - startOfNextDayDiffMs);
    const boundary =
      new Date(
        shifted.getFullYear(),
        shifted.getMonth(),
        shifted.getDate() + 1,
      ).getTime() + startOfNextDayDiffMs;
    const segmentEnd = Math.min(endAt, Math.max(boundary, cursor + 1));
    result.push({
      dayStr: getDbDateStr(shifted),
      durationMs: segmentEnd - cursor,
    });
    cursor = segmentEnd;
  }
  return result;
};

/** Parses the queue the native side hands over; anything malformed is dropped. */
export const parseHabitWidgetEvents = (json: string): HabitWidgetEvent[] => {
  let raw: unknown;
  try {
    raw = JSON.parse(json);
  } catch (e) {
    return [];
  }
  if (!Array.isArray(raw)) {
    return [];
  }
  const events: HabitWidgetEvent[] = [];
  for (const entry of raw as Record<string, unknown>[]) {
    if (
      !entry ||
      typeof entry.id !== 'string' ||
      !entry.id ||
      typeof entry.habitId !== 'string' ||
      !entry.habitId ||
      !EVENT_KINDS.includes(entry.kind as HabitWidgetEventKind) ||
      typeof entry.at !== 'number' ||
      !Number.isFinite(entry.at)
    ) {
      continue;
    }
    events.push({
      id: entry.id,
      habitId: entry.habitId,
      kind: entry.kind as HabitWidgetEventKind,
      at: entry.at,
      value: typeof entry.value === 'number' ? entry.value : 0,
      startAt: typeof entry.startAt === 'number' ? entry.startAt : 0,
      endAt: typeof entry.endAt === 'number' ? entry.endAt : 0,
    });
  }
  return events.sort((a, b) => a.at - b.at || a.id.localeCompare(b.id));
};

/**
 * Decides what the queued widget taps change. Pure: no store access, no dispatching.
 *
 * The day a tap is booked on comes from WHEN IT WAS MADE (`event.at`), never from when
 * the app happens to run — so a tap from 23:50 lands on that day even if it is applied
 * the next morning. Several taps on one habit and day are folded into one absolute
 * value, the way the habit page writes them (setCounterForDate).
 *
 * Mismatches (the habit was deleted, or changed its type since the tap) are skipped
 * but still acknowledged, so they cannot pile up in the queue.
 */
export const planHabitWidgetEventChanges = (
  events: HabitWidgetEvent[],
  counters: Dictionary<SimpleCounter>,
  startOfNextDayDiffMs: number,
  appliedIds: ReadonlySet<string>,
): HabitWidgetPlan => {
  const handledIds: string[] = [];
  const original = new Map<string, number>();
  const current = new Map<string, number>();
  const target = new Map<string, { id: string; date: string }>();
  const stopIds = new Set<string>();

  const keyFor = (counter: SimpleCounter, date: string): string => {
    const key = `${counter.id}|${date}`;
    if (!current.has(key)) {
      const value = counter.countOnDay?.[date] ?? 0;
      original.set(key, value);
      current.set(key, value);
      target.set(key, { id: counter.id, date });
    }
    return key;
  };

  for (const event of events) {
    handledIds.push(event.id);
    if (appliedIds.has(event.id)) {
      continue;
    }
    const counter = counters[event.habitId];
    if (!counter) {
      continue;
    }
    const isStopWatch = counter.type === SimpleCounterType.StopWatch;

    switch (event.kind) {
      case 'count': {
        if (isStopWatch) break;
        const key = keyFor(counter, getLogicalDayStr(event.at, startOfNextDayDiffMs));
        current.set(key, (current.get(key) as number) + 1);
        break;
      }
      case 'bool': {
        if (isStopWatch) break;
        const key = keyFor(counter, getLogicalDayStr(event.at, startOfNextDayDiffMs));
        const value = current.get(key) as number;
        current.set(key, event.value === 1 ? Math.max(value, 1) : 0);
        break;
      }
      case 'time': {
        if (!isStopWatch) break;
        for (const segment of splitIntoLogicalDays(
          event.startAt,
          event.endAt,
          startOfNextDayDiffMs,
        )) {
          const key = keyFor(counter, segment.dayStr);
          current.set(key, (current.get(key) as number) + Math.round(segment.durationMs));
        }
        break;
      }
      case 'stopApp': {
        if (isStopWatch && counter.isOn) {
          stopIds.add(counter.id);
        }
        break;
      }
    }
  }

  const changes: HabitWidgetChange[] = [];
  for (const [key, info] of target) {
    const newVal = current.get(key) as number;
    if (newVal !== original.get(key)) {
      changes.push({ type: 'setForDate', id: info.id, date: info.date, newVal });
    }
  }
  for (const id of stopIds) {
    changes.push({ type: 'setOff', id });
  }
  return { changes, handledIds };
};
