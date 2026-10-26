/**
 * Organizations (tenants), memberships and organization roles, invitations, teams,
 * organization settings and the audit-log viewer.
 *
 * <p>Public API: {@link com.jtac.organization.OrganizationAccess} (tenant resolution and
 * membership checks used by every tenant-scoped module), {@link com.jtac.organization.OrgContext},
 * {@link com.jtac.organization.OrgRole}, {@link com.jtac.organization.OrganizationSettings}.
 */
@ApplicationModule(displayName = "Organizations")
package com.jtac.organization;

import org.springframework.modulith.ApplicationModule;
