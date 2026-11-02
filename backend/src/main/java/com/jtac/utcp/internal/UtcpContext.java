package com.jtac.utcp.internal;

import com.jtac.identity.CurrentUser;
import com.jtac.organization.OrgContext;
import java.util.UUID;

/**
 * Validated security context for a single UTCP tool call.
 */
record UtcpContext(
        CurrentUser user,
        OrgContext org,
        String clientName,
        String correlationId
) {

    UUID userId() {
        return user.id();
    }

    UUID organizationId() {
        return org.organizationId();
    }

    String orgSlug() {
        return org.slug();
    }
}

