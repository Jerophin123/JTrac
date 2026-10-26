import { useMutation, useQueryClient } from '@tanstack/react-query';
import { call } from '../../api/call';
import { api } from '../../api/client';
import { keys } from '../../api/queries';
import type { IssueView } from '../../api/types';
import { describeError, fieldErrors } from '../../lib/errors';
import { useToast } from '../../ui/Toast';

/**
 * PATCHes an issue with optimistic locking (the version the user saw). The fresh issue
 * replaces the cached one; lists and boards are refetched.
 */
export function useIssueUpdate(org: string, issue: IssueView) {
  const queryClient = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: (changes: Record<string, unknown>) =>
      call(() =>
        api.PATCH('/api/v1/orgs/{org}/issues/{issueKey}', {
          params: { path: { org, issueKey: issue.key } },
          body: { changes, version: issue.version },
        }),
      ),
    onSuccess: async (updated) => {
      queryClient.setQueryData(keys.issue(org, issue.key), updated);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: keys.issuesRoot(org) }),
        queryClient.invalidateQueries({ queryKey: keys.activity(org, issue.key) }),
      ]);
    },
    onError: (error) => {
      const fields = Object.values(fieldErrors(error));
      toast.error(fields.length > 0 ? fields.join(' ') : describeError(error));
    },
  });
}
