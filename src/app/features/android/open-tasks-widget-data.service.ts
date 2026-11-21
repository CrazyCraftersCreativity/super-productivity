import { inject, Injectable } from '@angular/core';
import { Store } from '@ngrx/store';
import { firstValueFrom } from 'rxjs';
import { androidInterface } from './android-interface';
import {
  ANDROID_OPEN_TASKS_WIDGET_DATA_KEY,
  AndroidInterfaceWithOpenTasksWidget,
} from './android-open-tasks-widget.model';
import { selectAndroidOpenTasksWidgetData } from './store/android-open-tasks-widget.selectors';
import { DroidLog } from '../../core/log';

/**
 * Pushes the current open-tasks snapshot to the native KeyValStore for the "Open
 * tasks" home screen widget. Dedupes against the last successfully pushed blob so every
 * trigger path can call it unconditionally; pushes run strictly one after another so an
 * older snapshot can never overwrite a newer one.
 */
@Injectable({ providedIn: 'root' })
export class OpenTasksWidgetDataService {
  private _store = inject(Store);
  private _lastPushedJson: string | null = null;
  private _queue: Promise<void> = Promise.resolve();

  /**
   * @param isForce push even if nothing changed. Used on every app resume so the
   * widget redraws (and drops its "outdated" marker) after a refresh.
   */
  pushCurrent(isForce = false): Promise<void> {
    this._queue = this._queue.then(() => this._push(isForce));
    return this._queue;
  }

  private async _push(isForce: boolean): Promise<void> {
    try {
      // Re-read the store at push time: fresher than whatever triggered the push.
      const data = await firstValueFrom(this._store.select(selectAndroidOpenTasksWidgetData));
      const json = JSON.stringify(data);
      if (!isForce && json === this._lastPushedJson) {
        return;
      }
      const bridge = androidInterface as AndroidInterfaceWithOpenTasksWidget;
      await bridge.saveToDbWrapped(ANDROID_OPEN_TASKS_WIDGET_DATA_KEY, json);
      bridge.updateOpenTasksWidget?.();
      this._lastPushedJson = json;
    } catch (e) {
      DroidLog.err('Failed to push open tasks widget data', e);
    }
  }
}
