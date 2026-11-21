import { inject, Injectable } from '@angular/core';
import { createEffect } from '@ngrx/effects';
import { Store } from '@ngrx/store';
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
import { AndroidInterfaceWithOpenTasksWidget } from '../android-open-tasks-widget.model';
import { OpenTasksWidgetDataService } from '../open-tasks-widget-data.service';
import { selectAndroidOpenTasksWidgetData } from './android-open-tasks-widget.selectors';
import { HydrationStateService } from '../../../op-log/apply/hydration-state.service';
import { DataInitStateService } from '../../../core/data-init/data-init-state.service';

/**
 * Keeps the "Open tasks" home screen widget in sync with the app: pushes its snapshot
 * whenever it changes, after sync and on pause, and once on every resume (also lets the
 * app go back home if the widget's refresh button opened it). Done-taps made on the
 * widget are applied by AndroidWidgetEffects, which drains the same queue as the Today
 * widget's; the resulting task change then triggers the push here.
 */
@Injectable()
export class AndroidOpenTasksWidgetEffects {
  private _store = inject(Store);
  private _openTasksWidgetDataService = inject(OpenTasksWidgetDataService);
  private _hydrationState = inject(HydrationStateService);
  private _dataInitState = inject(DataInitStateService);

  // The selector emission is only a change trigger; pushCurrent() re-reads the store
  // at push time and dedupes itself.
  pushOnStateChange$ =
    IS_ANDROID_WEB_VIEW &&
    createEffect(
      () =>
        this._store.select(selectAndroidOpenTasksWidgetData).pipe(
          filter(() => !this._hydrationState.isApplyingRemoteOps()),
          map((data) => JSON.stringify(data)),
          distinctUntilChanged(),
          debounceTime(500),
          tap(() => this._openTasksWidgetDataService.pushCurrent()),
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
          tap(() => this._openTasksWidgetDataService.pushCurrent()),
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
          tap(() => this._openTasksWidgetDataService.pushCurrent()),
        ),
      { dispatch: false },
    );

  // Cold start and background -> foreground (onResume$ replays its last value). The
  // forced push redraws the widget even if the data did not change, which is also what
  // clears its "outdated" marker.
  pushOnResume$ =
    IS_ANDROID_WEB_VIEW &&
    createEffect(
      () =>
        androidInterface.onResume$.pipe(
          concatMap(() => this._dataInitState.isAllDataLoadedInitially$.pipe(first())),
          concatMap(async () => {
            await this._openTasksWidgetDataService.pushCurrent(true);
            (androidInterface as AndroidInterfaceWithOpenTasksWidget).habitWidgetRefreshDone?.();
          }),
        ),
      { dispatch: false },
    );
}
