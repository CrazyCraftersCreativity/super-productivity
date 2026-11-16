import { inject, Injectable } from '@angular/core';
import { createEffect } from '@ngrx/effects';
import { Store } from '@ngrx/store';
import { firstValueFrom, merge } from 'rxjs';
import {
  concatMap,
  debounceTime,
  distinctUntilChanged,
  filter,
  first,
  map,
  pairwise,
  tap,
} from 'rxjs/operators';
import { IS_ANDROID_WEB_VIEW } from '../../../util/is-android-web-view';
import { androidInterface } from '../android-interface';
import { AndroidInterfaceWithHabitWidget } from '../android-habit-widget.model';
import { HabitWidgetDataService } from '../habit-widget-data.service';
import { planHabitWidgetEventChanges, parseHabitWidgetEvents } from '../habit-widget-events';
import { selectAndroidHabitWidgetData } from './android-habit-widget.selectors';
import { SimpleCounterService } from '../../simple-counter/simple-counter.service';
import { selectSimpleCounterFeatureState } from '../../simple-counter/store/simple-counter.reducer';
import { setSimpleCounterCounterOff } from '../../simple-counter/store/simple-counter.actions';
import { DateService } from '../../../core/date/date.service';
import { HydrationStateService } from '../../../op-log/apply/hydration-state.service';
import { DataInitStateService } from '../../../core/data-init/data-init-state.service';
import { DroidLog } from '../../../core/log';

const APPLIED_IDS_STORAGE_KEY = 'SP_HABIT_WIDGET_APPLIED_EVENT_IDS';
const MAX_REMEMBERED_APPLIED_IDS = 300;

// Events are acknowledged right after they are applied, so this memory only matters if
// the app dies in between: the redelivered events are then recognised and skipped
// instead of counted twice.
const readAppliedIds = (): Set<string> => {
  try {
    const raw = localStorage.getItem(APPLIED_IDS_STORAGE_KEY);
    const parsed: unknown = raw ? JSON.parse(raw) : [];
    return new Set(
      Array.isArray(parsed) ? parsed.filter((id) => typeof id === 'string') : [],
    );
  } catch (e) {
    return new Set();
  }
};

const writeAppliedIds = (ids: Iterable<string>): void => {
  try {
    localStorage.setItem(
      APPLIED_IDS_STORAGE_KEY,
      JSON.stringify([...ids].slice(-MAX_REMEMBERED_APPLIED_IDS)),
    );
  } catch (e) {
    DroidLog.err('Failed to remember applied habit widget events', e);
  }
};

/**
 * Keeps the habits home screen widget in sync with the app:
 *  - pushes the habits snapshot whenever it changes, after sync, and on pause;
 *  - applies the taps queued by the widget (while the app was closed or alive) to the
 *    day they were made on, then pushes fresh data and tells native it may close the
 *    app again if the widget's refresh button opened it.
 */
@Injectable()
export class AndroidHabitWidgetEffects {
  private _store = inject(Store);
  private _habitWidgetDataService = inject(HabitWidgetDataService);
  private _simpleCounterService = inject(SimpleCounterService);
  private _dateService = inject(DateService);
  private _hydrationState = inject(HydrationStateService);
  private _dataInitState = inject(DataInitStateService);

  // The selector emission is only a change trigger; pushCurrent() re-reads the store
  // at push time and dedupes itself. The JSON comparison drops the re-emissions a
  // running stopwatch causes every second without changing what the widget shows.
  pushOnStateChange$ =
    IS_ANDROID_WEB_VIEW &&
    createEffect(
      () =>
        this._store.select(selectAndroidHabitWidgetData).pipe(
          filter(() => !this._hydrationState.isApplyingRemoteOps()),
          map((data) => JSON.stringify(data)),
          distinctUntilChanged(),
          debounceTime(500),
          tap(() => this._habitWidgetDataService.pushCurrent()),
        ),
      { dispatch: false },
    );

  // The guard above drops ALL emissions while remote ops are applied and nothing
  // re-emits afterwards, so push once whenever the sync window closes.
  pushOnSyncWindowEnd$ =
    IS_ANDROID_WEB_VIEW &&
    createEffect(
      () =>
        this._hydrationState.isInSyncWindow$.pipe(
          pairwise(),
          filter(([wasInWindow, isInWindow]) => wasInWindow && !isInWindow),
          tap(() => this._habitWidgetDataService.pushCurrent()),
        ),
      { dispatch: false },
    );

  // Last chance to hand the freshest state to the widget before the WebView may be
  // frozen or killed in the background — deliberately not debounced.
  pushOnPause$ =
    IS_ANDROID_WEB_VIEW &&
    createEffect(
      () =>
        androidInterface.onPause$.pipe(
          tap(() => this._habitWidgetDataService.pushCurrent()),
        ),
      { dispatch: false },
    );

  // Cold start and background -> foreground are covered by onResume$ (ReplaySubject(1)),
  // taps made while the app is alive by the contentless native drain signal (shared
  // with the task widget; draining twice is harmless because acknowledged events are gone).
  drainEvents$ =
    IS_ANDROID_WEB_VIEW &&
    createEffect(
      () =>
        merge(
          androidInterface.onResume$,
          androidInterface.onWidgetDoneDrainRequest$,
        ).pipe(
          concatMap(() => this._dataInitState.isAllDataLoadedInitially$.pipe(first())),
          concatMap(() => this._drainAndPush()),
        ),
      { dispatch: false },
    );

  private async _drainAndPush(): Promise<void> {
    try {
      await this._drainEvents();
    } catch (e) {
      DroidLog.err('Failed to apply habit widget events', e);
    }
    // Forced: after a refresh the widget must redraw even if the data did not change,
    // which is also what clears its "outdated" marker.
    await this._habitWidgetDataService.pushCurrent(true);
    (androidInterface as AndroidInterfaceWithHabitWidget).habitWidgetRefreshDone?.();
  }

  private async _drainEvents(): Promise<void> {
    const bridge = androidInterface as AndroidInterfaceWithHabitWidget;
    const json = bridge.getHabitWidgetEvents?.();
    if (!json) {
      return;
    }
    const events = parseHabitWidgetEvents(json);
    if (events.length === 0) {
      return;
    }

    const counterState = await firstValueFrom(
      this._store.select(selectSimpleCounterFeatureState),
    );
    const appliedIds = readAppliedIds();
    const plan = planHabitWidgetEventChanges(
      events,
      counterState.entities,
      this._dateService.getStartOfNextDayDiffMs(),
      appliedIds,
    );

    for (const change of plan.changes) {
      if (change.type === 'setForDate') {
        this._simpleCounterService.setCounterForDate(
          change.id,
          change.date,
          change.newVal,
        );
      } else {
        this._store.dispatch(setSimpleCounterCounterOff({ id: change.id }));
      }
    }
    DroidLog.log('Applied habit widget events', {
      eventCount: plan.handledIds.length,
      changeCount: plan.changes.length,
    });

    // Remember first, acknowledge second: a crash in between must not double-count.
    writeAppliedIds([...appliedIds, ...plan.handledIds]);
    bridge.acknowledgeHabitWidgetEvents?.(JSON.stringify(plan.handledIds));
  }
}
