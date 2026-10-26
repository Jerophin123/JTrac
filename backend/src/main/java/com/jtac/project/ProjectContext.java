package com.jtac.project;

import com.jtac.organization.OrgContext;
import com.jtac.shared.web.ApiErrors;
import java.util.Set;
import java.util.UUID;

/**
 * A request's resolved project with the caller's effective project roles and permissions.
 * Obtained only through {@link ProjectAccess}, which proves at least {@code BROWSE}.
 * Archived projects are read-only: only BROWSE (and MANAGE_PROJECT, to restore) remain.
 */
public record ProjectContext(ProjectRef project, OrgContext org, Set<UUID> roleIds, Set<ProjectPermission> permissions) {

    public UUID projectId() {
        return project.id();
    }

    public UUID organizationId() {
        return project.organizationId();
    }

    public UUID actorId() {
        return org.actorId();
    }

    public boolean has(ProjectPermission permission) {
        return permissions.contains(permission);
    }

    public ProjectContext require(ProjectPermission permission) {
        if (!has(permission)) {
            if (project.archived() && permission != ProjectPermission.BROWSE) {
                throw ApiErrors.forbidden("This project is archived and read-only.");
            }
            throw ApiErrors.forbidden("You do not have permission to do this in " + project.key() + ".");
        }
        return this;
    }
}
