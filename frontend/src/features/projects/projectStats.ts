import { queryOptions } from '@tanstack/react-query';
import { call } from '../../api/call';
import { api } from '../../api/client';
import { keys } from '../../api/queries';
import type { IssueFilter } from '../../api/types';

export interface ProjectStats {
  total: number;
  todo: number;
  inProgress: number;
  done: number;
  /** Open issues assigned to the caller. */
  mine: number;
}

export const EMPTY_STATS: ProjectStats = { total: 0, todo: 0, inProgress: 0, done: 0, mine: 0 };

/**
 * Issue counts per project for the project cards, from five org-wide aggregate calls
 * (grouped by project, so the cost does not grow with the number of projects). The server
 * counts only issues the caller may see. Rooted under the issue keys, so realtime issue
 * events refresh it.
 */
export const projectStatsQuery = (org: string) =>
  queryOptions({
    queryKey: [...keys.issuesRoot(org), 'project-stats'] as const,
    queryFn: async (): Promise<Map<string, ProjectStats>> => {
      const count = (filter: IssueFilter) =>
        call(() => api.POST('/api/v1/orgs/{org}/issues/aggregate', { params: { path: { org } }, body: { groupBy: 'project', filter } }));
      const [total, todo, inProgress, done, mine] = await Promise.all([
        count({}),
        count({ categories: ['TODO'] }),
        count({ categories: ['IN_PROGRESS'] }),
        count({ categories: ['DONE'] }),
        count({ assignees: ['me'], resolved: false }),
      ]);
      const stats = new Map<string, ProjectStats>();
      const add = (buckets: typeof total, field: keyof ProjectStats) => {
        for (const bucket of buckets) {
          if (bucket.key) {
            const entry = stats.get(bucket.key) ?? { ...EMPTY_STATS };
            entry[field] = bucket.count;
            stats.set(bucket.key, entry);
          }
        }
      };
      add(total, 'total');
      add(todo, 'todo');
      add(inProgress, 'inProgress');
      add(done, 'done');
      add(mine, 'mine');
      return stats;
    },
    staleTime: 30_000,
  });
