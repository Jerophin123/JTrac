package com.jtac.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditRecord(UUID id, UUID organizationId, UUID actorId, String action, String targetType,
        String targetId, Map<String, Object> details, String clientAddress, Instant occurredAt) {
}
