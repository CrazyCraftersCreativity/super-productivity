import type { AndroidInterface } from './android-interface';
import type { AndroidWidgetTask } from './android-widget.model';

/**
 * Contract for the `widget_data_open_tasks` KeyValStore blob of the "Open tasks"
 * home screen widget (tasks that are not done, not recurring and not planned for a
 * day). The native reader is OpenTasksWidgetProvider/Service, which parses it with
 * WidgetData (the shape deliberately matches the Today widget's `widget_data`, minus
 * the tracked task) — keep both ends in sync and bump `v` on breaking changes.
 *
 * Angular is the ONLY writer. Checkbox taps are queued natively in WidgetDoneQueue (the
 * Today widget's queue, drained by AndroidWidgetEffects) and hidden natively at render
 * time, never written into the blob.
 */
export const ANDROID_OPEN_TASKS_WIDGET_DATA_KEY = 'widget_data_open_tasks';

export interface AndroidOpenTasksWidgetData {
  v: 1;
  /** DISPLAY ONLY: the logical day this snapshot was written on (see AndroidWidgetData). */
  dayStr: string;
  /** Epoch ms at which the snapshot counts as outdated (end of `dayStr`). */
  validUntil: number;
  tasks: AndroidWidgetTask[];
  projectColors: { [projectId: string]: string };
}

/** Native bridge methods of the open tasks widget (JavaScriptInterface.kt). Optional: older APKs lack them. */
export interface OpenTasksWidgetBridge {
  // Re-render the open tasks widget from the current widget_data_open_tasks snapshot
  updateOpenTasksWidget?(): void;
  // Shared with the habits widget: data is fresh, the app may go back home if a
  // widget's refresh button opened it
  habitWidgetRefreshDone?(): void;
}

export type AndroidInterfaceWithOpenTasksWidget = AndroidInterface & OpenTasksWidgetBridge;
