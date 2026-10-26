package com.jtac.workflow;

import java.util.Map;
import java.util.UUID;

/**
 * Published synchronously inside the publishing transaction. Listeners must move every
 * issue in a key status of {@code statusMapping} to the mapped status before commit.
 */
public record WorkflowPublished(UUID organizationId, UUID projectId, UUID versionId, int versionNo,
        Map<UUID, UUID> statusMapping, UUID actorId) {
}
