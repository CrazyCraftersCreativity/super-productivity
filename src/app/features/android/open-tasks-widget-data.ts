import type { Task } from '../tasks/task.model';
import type { AndroidOpenTasksWidgetData } from './android-open-tasks-widget.model';

/**
 * A task the "Open tasks" widget lists: a top-level task that is not done, not an
 * instance of a repeating task and not planned for any day or time. "Planned" means
 * the same thing as for the Today list (dueDay / dueWithTime); deadlines do not count
 * as planning.
 */
export const isUnscheduledOpenTask = (
  task: Pick<Task, 'isDone' | 'parentId' | 'repeatCfgId' | 'dueDay' | 'dueWithTime'>,
): boolean =>
  !task.isDone &&
  !task.parentId &&
  !task.repeatCfgId &&
  !task.dueDay &&
  !task.dueWithTime;

/**
 * The widget blob for the given tasks, newest task first. Pure: the day boundary comes
 * in as an argument (`validUntil`), like for the Today and habits widgets.
 */
export const buildOpenTasksWidgetData = (
  tasks: readonly Pick<
    Task,
    | 'id'
    | 'title'
    | 'created'
    | 'projectId'
    | 'isDone'
    | 'parentId'
    | 'repeatCfgId'
    | 'dueDay'
    | 'dueWithTime'
  >[],
  projectPrimaryColors: Readonly<Record<string, string | undefined>>,
  dayStr: string,
  validUntil: number,
): AndroidOpenTasksWidgetData => {
  const open = tasks
    .filter(isUnscheduledOpenTask)
    .sort((a, b) => b.created - a.created || a.id.localeCompare(b.id));

  const projectColors: { [projectId: string]: string } = {};
  const widgetTasks = open.map((task) => {
    // projectId is omitted (not null) without a project: org.json's optString maps
    // JSON null to the literal string "null"
    const widgetTask: AndroidOpenTasksWidgetData['tasks'][number] = {
      id: task.id,
      title: task.title,
      isDone: false,
    };
    if (task.projectId) {
      widgetTask.projectId = task.projectId;
      const color = projectPrimaryColors[task.projectId];
      if (color) {
        projectColors[task.projectId] = color;
      }
    }
    return widgetTask;
  });

  return { v: 1, dayStr, validUntil, tasks: widgetTasks, projectColors };
};
