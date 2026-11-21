import { createSelector } from '@ngrx/store';
import { selectAllTasksInActiveProjects } from '../../tasks/store/task.selectors';
import { selectProjectFeatureState } from '../../project/store/project.selectors';
import {
  selectStartOfNextDayDiffMs,
  selectTodayStr,
} from '../../../root-store/app-state/app-state.selectors';
import { AndroidOpenTasksWidgetData } from '../android-open-tasks-widget.model';
import { buildOpenTasksWidgetData } from '../open-tasks-widget-data';
import { getWidgetValidUntil } from './android-widget.selectors';

/**
 * Projects the unscheduled, open tasks into the exact `widget_data_open_tasks` blob
 * shape (see buildOpenTasksWidgetData). The memoization gives referential stability;
 * AndroidOpenTasksWidgetEffects compares the JSON before pushing.
 */
export const selectAndroidOpenTasksWidgetData = createSelector(
  selectAllTasksInActiveProjects,
  selectProjectFeatureState,
  selectTodayStr,
  selectStartOfNextDayDiffMs,
  (tasks, projectState, dayStr, startOfNextDayDiffMs): AndroidOpenTasksWidgetData => {
    const colors: Record<string, string | undefined> = {};
    for (const task of tasks) {
      if (task.projectId && !(task.projectId in colors)) {
        colors[task.projectId] = projectState.entities[task.projectId]?.theme?.primary;
      }
    }
    return buildOpenTasksWidgetData(
      tasks,
      colors,
      dayStr,
      getWidgetValidUntil(dayStr, startOfNextDayDiffMs),
    );
  },
);
