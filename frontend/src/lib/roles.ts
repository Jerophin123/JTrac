export type OrgRole = 'OWNER' | 'ADMIN' | 'MEMBER' | 'GUEST';

export const ORG_ROLE_LABELS: Record<OrgRole, string> = {
  OWNER: 'Owner',
  ADMIN: 'Admin',
  MEMBER: 'Member',
  GUEST: 'Guest',
};

export const ORG_ROLE_DESCRIPTIONS: Record<OrgRole, string> = {
  OWNER: 'Owner of organization',
  ADMIN: 'Admin of organization',
  MEMBER: 'Member of organization',
  GUEST: 'Guest of organization',
};

export function formatOrgRole(role: string | undefined): string {
  if (!role) {
    return '';
  }
  const labels: Record<string, string> = ORG_ROLE_LABELS;
  return labels[role] ?? role;
}

export function formatOrgRoleDescription(role: string | undefined): string {
  if (!role) {
    return '';
  }
  const descriptions: Record<string, string> = ORG_ROLE_DESCRIPTIONS;
  return descriptions[role] ?? `${role} of organization`;
}

