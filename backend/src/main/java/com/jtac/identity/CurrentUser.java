package com.jtac.identity;

import java.util.UUID;

/**
 * The authenticated user as other modules see it. Contains no credentials. Controllers
 * receive it as a method parameter (resolved from the session or an API token).
 *
 * @param viaApiToken         true when the request is authenticated by an API token
 * @param readOnly            true when authenticated by an API token without the {@code write} scope
 * @param tokenOrganizationId when authenticated by an organization-restricted API token, the
 *                            only organization it may access; {@code null} otherwise
 */
public record CurrentUser(UUID id, String email, String displayName, String handle, boolean viaApiToken,
        boolean readOnly, UUID tokenOrganizationId) {

    public CurrentUser(UUID id, String email, String displayName, String handle) {
        this(id, email, displayName, handle, false, false, null);
    }

    /** Whether this principal may act in {@code organizationId} at all (token restriction). */
    public boolean mayAccessOrganization(UUID organizationId) {
        return tokenOrganizationId == null || tokenOrganizationId.equals(organizationId);
    }
}
