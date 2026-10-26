import { useQuery, useSuspenseQuery } from '@tanstack/react-query';
import { useParams } from '@tanstack/react-router';
import { orgQuery, projectQuery } from '../api/queries';
import type { ProjectPermission } from '../api/types';

/** The organization slug of the current route (routes below /$org only). */
export function useOrgSlug(): string {
  const { org } = useParams({ strict: false });
  if (!org) {
    throw new Error('useOrgSlug used outside an organization route');
  }
  return org;
}

/** The current organization (pre-loaded by the /$org route loader). */
export function useOrg() {
  return useSuspenseQuery(orgQuery(useOrgSlug())).data;
}

export function useProjectKey(): string {
  const { key } = useParams({ strict: false });
  if (!key) {
    throw new Error('useProjectKey used outside a project route');
  }
  return key;
}

/** The current project (pre-loaded by the project route loader). */
export function useProject() {
  return useSuspenseQuery(projectQuery(useOrgSlug(), useProjectKey())).data;
}

/** Whether the caller holds a permission in a project; for UI affordances only (the server decides). */
export function useProjectPermission(org: string, key: string, permission: ProjectPermission): boolean {
  const { data } = useQuery(projectQuery(org, key));
  return data?.myPermissions.includes(permission) ?? false;
}
