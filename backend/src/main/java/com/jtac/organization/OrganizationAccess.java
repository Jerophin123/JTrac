package com.jtac.organization;

import com.jtac.identity.CurrentUser;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Tenant resolution and membership queries. Every tenant-scoped request starts with
 * {@link #require}: an organization the caller does not belong to is reported as not found.
 */
public interface OrganizationAccess {

    /** Resolves {@code slug} for {@code user} or throws 404 (unknown, deleted, or not a member). */
    OrgContext require(CurrentUser user, String slug);

    Optional<OrgRole> roleOf(UUID organizationId, UUID userId);

    /** Of the given users, those who are members, with their roles. */
    Map<UUID, OrgRole> rolesOf(UUID organizationId, Collection<UUID> userIds);

    /** Teams the user belongs to in the organization. */
    Set<UUID> teamIdsOf(UUID organizationId, UUID userId);

    /** Members of a team (empty if the team does not belong to the organization). */
    Set<UUID> teamMembers(UUID organizationId, UUID teamId);

    boolean teamExists(UUID organizationId, UUID teamId);

    /** The organization's current slug (for links in notifications), unless deleted. */
    Optional<String> slugOf(UUID organizationId);
}
