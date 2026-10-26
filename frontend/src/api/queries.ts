import { keepPreviousData, queryOptions } from '@tanstack/react-query';
import { call } from './call';
import { api } from './client';
import type { IssueFilter } from './types';

/**
 * Query keys are rooted at ['org', slug] so a realtime event or a mutation can invalidate
 * everything of one organization (or one issue) precisely.
 */
export const keys = {
  orgs: ['orgs'] as const,
  org: (org: string) => ['org', org] as const,
  members: (org: string, q = '', page = 0) => ['org', org, 'members', q, page] as const,
  teams: (org: string) => ['org', org, 'teams'] as const,
  invitations: (org: string) => ['org', org, 'invitations'] as const,
  audit: (org: string, action: string, page: number) => ['org', org, 'audit', action, page] as const,
  projects: (org: string) => ['org', org, 'projects'] as const,
  project: (org: string, key: string) => ['org', org, 'project', key] as const,
  catalog: (org: string, key: string) => ['org', org, 'project', key, 'catalog'] as const,
  workflow: (org: string, key: string) => ['org', org, 'project', key, 'workflow'] as const,
  fields: (org: string, key: string) => ['org', org, 'project', key, 'fields'] as const,
  access: (org: string, key: string) => ['org', org, 'project', key, 'access'] as const,
  board: (org: string, key: string, filter: IssueFilter) => ['org', org, 'issues', 'board', key, filter] as const,
  search: (org: string, filter: IssueFilter, page: number, size: number) => ['org', org, 'issues', 'search', filter, page, size] as const,
  issuesRoot: (org: string) => ['org', org, 'issues'] as const,
  issue: (org: string, key: string) => ['org', org, 'issue', key] as const,
  activity: (org: string, key: string) => ['org', org, 'issue', key, 'activity'] as const,
  comments: (org: string, key: string) => ['org', org, 'issue', key, 'comments'] as const,
  attachments: (org: string, key: string) => ['org', org, 'issue', key, 'attachments'] as const,
  notifications: (org: string, unread: boolean) => ['org', org, 'notifications', unread] as const,
  unread: (org: string) => ['org', org, 'notifications', 'unread-count'] as const,
  preferences: (org: string) => ['org', org, 'notification-preferences'] as const,
  views: (org: string, project?: string) => ['org', org, 'views', project ?? null] as const,
  dashboards: (org: string) => ['org', org, 'dashboards'] as const,
  dashboard: (org: string, id: string) => ['org', org, 'dashboard', id] as const,
  widgetData: (org: string, dashboard: string, widget: string) => ['org', org, 'issues', 'widget', dashboard, widget] as const,
  timeline: (org: string, key: string, filter: IssueFilter) => ['org', org, 'issues', 'timeline', key, filter] as const,
  milestoneProgress: (org: string, key: string) => ['org', org, 'issues', 'milestones', key] as const,
  webhooks: (org: string) => ['org', org, 'webhooks'] as const,
  deliveries: (org: string, id: string, page: number) => ['org', org, 'webhooks', id, 'deliveries', page] as const,
  globalSearch: (org: string, q: string) => ['org', org, 'global-search', q] as const,
  profile: ['me'] as const,
  tokens: ['me', 'tokens'] as const,
};

const p = <T extends Record<string, string>>(path: T) => ({ params: { path } });

export const orgsQuery = queryOptions({
  queryKey: keys.orgs,
  queryFn: () => call(() => api.GET('/api/v1/orgs')),
});

export const orgQuery = (org: string) =>
  queryOptions({
    queryKey: keys.org(org),
    queryFn: () => call(() => api.GET('/api/v1/orgs/{org}', p({ org }))),
    staleTime: 60_000,
  });

export const projectsQuery = (org: string) =>
  queryOptions({
    queryKey: keys.projects(org),
    queryFn: () => call(() => api.GET('/api/v1/orgs/{org}/projects', p({ org }))),
  });

export const projectQuery = (org: string, key: string) =>
  queryOptions({
    queryKey: keys.project(org, key),
    queryFn: () => call(() => api.GET('/api/v1/orgs/{org}/projects/{key}', p({ org, key }))),
  });

export const catalogQuery = (org: string, key: string) =>
  queryOptions({
    queryKey: keys.catalog(org, key),
    queryFn: () => call(() => api.GET('/api/v1/orgs/{org}/projects/{key}/catalog', p({ org, key }))),
    staleTime: 60_000,
  });

export const workflowQuery = (org: string, key: string) =>
  queryOptions({
    queryKey: keys.workflow(org, key),
    queryFn: () => call(() => api.GET('/api/v1/orgs/{org}/projects/{key}/workflow', p({ org, key }))),
    staleTime: 60_000,
  });

export const fieldsQuery = (org: string, key: string) =>
  queryOptions({
    queryKey: keys.fields(org, key),
    queryFn: () => call(() => api.GET('/api/v1/orgs/{org}/projects/{key}/fields', p({ org, key }))),
    staleTime: 60_000,
  });

export const accessQuery = (org: string, key: string) =>
  queryOptions({
    queryKey: keys.access(org, key),
    queryFn: () => call(() => api.GET('/api/v1/orgs/{org}/projects/{key}/access', p({ org, key }))),
  });

export const membersQuery = (org: string, q = '', page = 0, size = 50) =>
  queryOptions({
    queryKey: keys.members(org, q, page),
    queryFn: () =>
      call(() => api.GET('/api/v1/orgs/{org}/members', { params: { path: { org }, query: { q: q || undefined, page, size } } })),
    placeholderData: keepPreviousData,
  });

export const teamsQuery = (org: string) =>
  queryOptions({
    queryKey: keys.teams(org),
    queryFn: () => call(() => api.GET('/api/v1/orgs/{org}/teams', p({ org }))),
  });

export const searchQuery = (org: string, filter: IssueFilter, page = 0, size = 50) =>
  queryOptions({
    queryKey: keys.search(org, filter, page, size),
    queryFn: () => call(() => api.POST('/api/v1/orgs/{org}/issues/search', { params: { path: { org } }, body: { filter, page, size } })),
    placeholderData: keepPreviousData,
  });

export const boardQuery = (org: string, key: string, filter: IssueFilter) =>
  queryOptions({
    queryKey: keys.board(org, key, filter),
    queryFn: () =>
      call(() =>
        api.POST('/api/v1/orgs/{org}/projects/{key}/board', {
          params: { path: { org, key }, query: { perColumn: 200 } },
          body: { filter },
        }),
      ),
    placeholderData: keepPreviousData,
  });

export const issueQuery = (org: string, issueKey: string) =>
  queryOptions({
    queryKey: keys.issue(org, issueKey),
    queryFn: () => call(() => api.GET('/api/v1/orgs/{org}/issues/{issueKey}', p({ org, issueKey }))),
  });

export const activityQuery = (org: string, issueKey: string) =>
  queryOptions({
    queryKey: keys.activity(org, issueKey),
    queryFn: () => call(() => api.GET('/api/v1/orgs/{org}/issues/{issueKey}/activity', p({ org, issueKey }))),
  });

export const commentsQuery = (org: string, issueKey: string) =>
  queryOptions({
    queryKey: keys.comments(org, issueKey),
    queryFn: () => call(() => api.GET('/api/v1/orgs/{org}/issues/{issueKey}/comments', p({ org, issueKey }))),
  });

export const attachmentsQuery = (org: string, issueKey: string) =>
  queryOptions({
    queryKey: keys.attachments(org, issueKey),
    queryFn: () => call(() => api.GET('/api/v1/orgs/{org}/issues/{issueKey}/attachments', p({ org, issueKey }))),
  });

export const unreadQuery = (org: string) =>
  queryOptions({
    queryKey: keys.unread(org),
    queryFn: () => call(() => api.GET('/api/v1/orgs/{org}/notifications/unread-count', p({ org }))),
    refetchInterval: 120_000,
  });

export const viewsQuery = (org: string, project?: string) =>
  queryOptions({
    queryKey: keys.views(org, project),
    queryFn: () => call(() => api.GET('/api/v1/orgs/{org}/views', { params: { path: { org }, query: { project } } })),
  });

export const dashboardsQuery = (org: string) =>
  queryOptions({
    queryKey: keys.dashboards(org),
    queryFn: () => call(() => api.GET('/api/v1/orgs/{org}/dashboards', p({ org }))),
  });

export const profileQuery = queryOptions({
  queryKey: keys.profile,
  queryFn: () => call(() => api.GET('/api/v1/me')),
});
