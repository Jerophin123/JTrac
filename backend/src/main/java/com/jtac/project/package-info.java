/**
 * Projects: lifecycle (create, edit, archive, delete), project roles with permission sets,
 * project membership (users and teams), issue types, labels and milestones.
 *
 * <p>Public API: {@link com.jtac.project.ProjectAccess} resolves what the current user may
 * do in a project (direct role, team roles, the organization default role for
 * organization-visible projects, and implicit administration for organization admins);
 * {@link com.jtac.project.ProjectContext}, {@link com.jtac.project.ProjectPermission},
 * {@link com.jtac.project.ProjectCatalog}.
 */
@ApplicationModule(displayName = "Projects")
package com.jtac.project;

import org.springframework.modulith.ApplicationModule;
