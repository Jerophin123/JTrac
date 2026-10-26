package com.jtac.organization;

import com.jtac.identity.CurrentUser;
import com.jtac.shared.web.ApiErrors;
import java.util.UUID;

/**
 * A request's resolved tenant: the organization and the caller's membership in it.
 * Obtained only through {@link OrganizationAccess#require}, which proves membership.
 */
public record OrgContext(UUID organizationId, String slug, String name, OrganizationSettings settings, OrgRole role,
        CurrentUser actor) {

    public UUID actorId() {
        return actor.id();
    }

    public boolean isAdmin() {
        return role.isAdmin();
    }

    public OrgContext requireAdmin() {
        if (!isAdmin()) {
            throw ApiErrors.forbidden("Only organization owners and admins can do this.");
        }
        return this;
    }

    public OrgContext requireOwner() {
        if (role != OrgRole.OWNER) {
            throw ApiErrors.forbidden("Only organization owners can do this.");
        }
        return this;
    }
}
