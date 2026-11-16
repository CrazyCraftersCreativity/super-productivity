import { inject, Injectable } from '@angular/core';
import { Store } from '@ngrx/store';
import { firstValueFrom } from 'rxjs';
import { androidInterface } from './android-interface';
import {
  AndroidHabitWidgetSnapshot,
  AndroidInterfaceWithHabitWidget,
  ANDROID_HABIT_WIDGET_DATA_KEY,
} from './android-habit-widget.model';
import { selectAndroidHabitWidgetData } from './store/android-habit-widget.selectors';
import { renderHabitIconBase64 } from './habit-widget-icon-renderer';
import { DroidLog } from '../../core/log';

/**
 * Pushes the current habits snapshot to the native KeyValStore for the habits home
 * screen widget. Dedupes against the last successfully pushed blob so every trigger
 * path can call it unconditionally; pushes run strictly one after another so an older
 * snapshot can never overwrite a newer one.
 */
@Injectable({ providedIn: 'root' })
export class HabitWidgetDataService {
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
      const data = await firstValueFrom(this._store.select(selectAndroidHabitWidgetData));

      const iconNames = [
        ...new Set(data.habits.map((h) => h.icon).filter((n): n is string => !!n)),
      ].sort();
      const rendered = await Promise.all(iconNames.map((n) => renderHabitIconBase64(n)));
      // Built in sorted order so the JSON is stable and the dedupe below works.
      const icons: AndroidHabitWidgetSnapshot['icons'] = {};
      iconNames.forEach((name, i) => {
        const png = rendered[i];
        if (png) {
          icons[name] = png;
        }
      });

      const json = JSON.stringify({ ...data, icons } satisfies AndroidHabitWidgetSnapshot);
      if (!isForce && json === this._lastPushedJson) {
        return;
      }
      const bridge = androidInterface as AndroidInterfaceWithHabitWidget;
      await bridge.saveToDbWrapped(ANDROID_HABIT_WIDGET_DATA_KEY, json);
      bridge.updateHabitWidget?.();
      this._lastPushedJson = json;
    } catch (e) {
      DroidLog.err('Failed to push habit widget data', e);
    }
  }
}
