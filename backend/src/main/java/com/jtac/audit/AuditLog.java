package com.jtac.audit;

import com.jtac.shared.web.PageResponse;
import java.util.UUID;

/** Append-only audit log. {@link #record} joins the caller's transaction. */
public interface AuditLog {

    void record(AuditEntry entry);

    /**
     * Newest first. Callers must have verified that the requester may read the
     * organization's audit log.
     */
    PageResponse<AuditRecord> forOrganization(UUID organizationId, String actionPrefix, int page, int size);
}
