package com.jtac.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * An audit event to record. Details must never contain secrets (passwords, tokens).
 *
 * @param organizationId the tenant, or {@code null} for platform-level events
 * @param actorId        the acting user, or {@code null} for anonymous/system actions
 */
public record AuditEntry(UUID organizationId, UUID actorId, String action, String targetType, String targetId,
        Map<String, Object> details) {

    public AuditEntry {
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static AuditEntry of(UUID organizationId, UUID actorId, String action) {
        return new AuditEntry(organizationId, actorId, action, null, null, Map.of());
    }

    public AuditEntry target(String type, Object id) {
        return new AuditEntry(organizationId, actorId, action, type, id == null ? null : id.toString(), details);
    }

    public AuditEntry detail(String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(details);
        if (value != null) {
            copy.put(key, value instanceof UUID ? value.toString() : value);
        }
        return new AuditEntry(organizationId, actorId, action, targetType, targetId, copy);
    }
}
