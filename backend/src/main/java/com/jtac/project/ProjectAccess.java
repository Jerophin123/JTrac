package com.jtac.project;

import com.jtac.organization.OrgContext;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Project-level authorization. A project the caller cannot browse is reported as not found.
 */
public interface ProjectAccess {

    ProjectContext require(OrgContext org, String projectKey);

    ProjectContext require(OrgContext org, UUID projectId);

    /** Contexts for every project of the organization the caller can browse. */
    List<ProjectContext> visible(OrgContext org);

    /** Contexts for the given projects that the caller can browse (others are omitted). */
    Map<UUID, ProjectContext> visible(OrgContext org, Collection<UUID> projectIds);

    /**
     * What another user (e.g. a notification recipient or an assignee candidate) may do in a
     * project: their effective role ids and permissions, or empty if they cannot browse it.
     */
    Optional<Grants> grantsFor(UUID organizationId, UUID projectId, UUID userId);

    /** Users of the organization who can browse the project (assignment and mention candidates). */
    Set<UUID> browsers(UUID organizationId, UUID projectId);

    record Grants(Set<UUID> roleIds, Set<ProjectPermission> permissions) {
        public boolean has(ProjectPermission permission) {
            return permissions.contains(permission);
        }
    }
}
