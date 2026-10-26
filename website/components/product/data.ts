/**
 * Illustrative demo content for the JTRAC replica. These are NOT real repository issues;
 * every replica is labelled "Demo data". The workflow is JTRAC's real "Structured" project
 * template (statuses, transitions, Approve limited to Administrators, Assignee required in
 * In Progress and In Review), from WorkflowService.
 */
export type Category = 'TODO' | 'IN_PROGRESS' | 'DONE' | 'CANCELED';
export type Priority = 'URGENT' | 'HIGH' | 'MEDIUM' | 'LOW' | 'NONE';

export interface Status {
  id: string;
  name: string;
  category: Category;
}

export const statuses: Status[] = [
  { id: 'todo', name: 'To Do', category: 'TODO' },
  { id: 'progress', name: 'In Progress', category: 'IN_PROGRESS' },
  { id: 'review', name: 'In Review', category: 'IN_PROGRESS' },
  { id: 'done', name: 'Done', category: 'DONE' },
  { id: 'canceled', name: 'Canceled', category: 'CANCELED' },
];

export const statusById = Object.fromEntries(statuses.map((s) => [s.id, s])) as Record<string, Status>;

export interface Transition {
  name: string;
  from: string[];
  to: string;
  adminOnly?: boolean;
}

export const transitions: Transition[] = [
  { name: 'Start progress', from: ['todo'], to: 'progress' },
  { name: 'Submit for review', from: ['progress'], to: 'review' },
  { name: 'Request changes', from: ['review'], to: 'progress' },
  { name: 'Approve', from: ['review'], to: 'done', adminOnly: true },
  { name: 'Reopen', from: ['done', 'canceled'], to: 'todo' },
  { name: 'Cancel', from: ['todo', 'progress', 'review'], to: 'canceled' },
];

/** Field rule from the template: Assignee is required in these statuses. */
export const assigneeRequiredIn = new Set(['progress', 'review']);

export interface Label {
  name: string;
  color: string;
}

export interface DemoIssue {
  key: string;
  title: string;
  statusId: string;
  priority: Priority;
  assignee: string | null;
  labels: Label[];
  milestone: string | null;
  due: string | null;
  overdue?: boolean;
  updated: string;
  description: string[];
  /** Timeline bar as days relative to the demo "today" (Nov 5); inclusive. */
  span: [start: number, end: number] | null;
  history: { who: string; what: string; when: string }[];
}

const label = {
  dashboards: { name: 'dashboards', color: '#3987e5' },
  ui: { name: 'ui', color: '#8cbcd6' },
  search: { name: 'search', color: '#6fbf8e' },
  notifications: { name: 'notifications', color: '#e2b05a' },
  planning: { name: 'planning', color: '#e07c50' },
  export: { name: 'import-export', color: '#a59b90' },
};

export const demoIssues: DemoIssue[] = [
  {
    key: 'JTRAC-101',
    title: 'Improve project dashboard',
    statusId: 'progress',
    priority: 'HIGH',
    assignee: 'Maya Kapoor',
    labels: [label.dashboards, label.ui],
    milestone: 'Spring release',
    due: 'Nov 12',
    updated: '24 min ago',
    description: [
      'Group the milestone progress and created-vs-resolved widgets at the top of the project dashboard.',
      'Widget data must stay computed for the viewer, so guests only see counts for issues they can open.',
    ],
    span: [0, 7],
    history: [
      { who: 'Arjun Rao', what: 'created the issue', when: '3 days ago' },
      { who: 'Maya Kapoor', what: 'changed status To Do → In Progress', when: '2 hours ago' },
      { who: 'Arjun Rao', what: 'commented', when: '1 hour ago' },
    ],
  },
  {
    key: 'JTRAC-102',
    title: 'Refine issue filtering',
    statusId: 'review',
    priority: 'MEDIUM',
    assignee: 'Arjun Rao',
    labels: [label.search],
    milestone: 'Spring release',
    due: 'Nov 8',
    updated: '3 hours ago',
    description: ['Let saved views combine status, assignee and label filters, and share them with the team.'],
    span: [-4, 3],
    history: [
      { who: 'Arjun Rao', what: 'changed status In Progress → In Review', when: '3 hours ago' },
      { who: 'Jo Lin', what: 'linked relates to JTRAC-101', when: '2 hours ago' },
    ],
  },
  {
    key: 'JTRAC-103',
    title: 'Update notification preferences',
    statusId: 'todo',
    priority: 'LOW',
    assignee: null,
    labels: [label.notifications],
    milestone: null,
    due: null,
    updated: 'yesterday',
    description: ['Allow each member to choose which events send an email and which only reach the inbox.'],
    span: null,
    history: [{ who: 'Sam Diaz', what: 'created the issue', when: 'yesterday' }],
  },
  {
    key: 'JTRAC-104',
    title: 'Add release milestone to timeline',
    statusId: 'todo',
    priority: 'MEDIUM',
    assignee: 'Jo Lin',
    labels: [label.planning],
    milestone: 'Spring release',
    due: 'Nov 4',
    overdue: true,
    updated: '5 hours ago',
    description: ['Show the Spring release milestone on the project timeline and mark issues that block it.'],
    span: [-3, -1],
    history: [{ who: 'Jo Lin', what: 'linked blocks JTRAC-101', when: '5 hours ago' }],
  },
  {
    key: 'JTRAC-105',
    title: 'Require root cause before Done',
    statusId: 'progress',
    priority: 'URGENT',
    assignee: 'Maya Kapoor',
    labels: [],
    milestone: 'Spring release',
    due: 'Nov 10',
    updated: '40 min ago',
    description: ['Add a “Root cause” custom field and make it required when the issue reaches In Review.'],
    span: [1, 5],
    history: [{ who: 'Maya Kapoor', what: 'changed priority High → Urgent', when: '40 min ago' }],
  },
  {
    key: 'JTRAC-106',
    title: 'Export filtered list to CSV',
    statusId: 'done',
    priority: 'LOW',
    assignee: 'Arjun Rao',
    labels: [label.export],
    milestone: 'Winter release',
    due: 'Oct 30',
    updated: '2 days ago',
    description: ['Export the issues that match the current filter, with the visible columns.'],
    span: [-7, -6],
    history: [{ who: 'Arjun Rao', what: 'changed status In Review → Done', when: '2 days ago' }],
  },
];
