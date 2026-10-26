package com.jtac.audit.internal;

import com.jtac.audit.AuditEntry;
import com.jtac.audit.AuditLog;
import com.jtac.audit.AuditRecord;
import com.jtac.shared.Ids;
import com.jtac.shared.web.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
class JdbcAuditLog implements AuditLog {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;

    JdbcAuditLog(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional
    public void record(AuditEntry entry) {
        jdbc.sql("""
                INSERT INTO audit_event (id, organization_id, actor_id, action, target_type, target_id, details, client_address)
                VALUES (:id, :org, :actor, :action, :targetType, :targetId, CAST(:details AS jsonb), :client)
                """)
                .param("id", Ids.newId())
                .param("org", entry.organizationId())
                .param("actor", entry.actorId())
                .param("action", entry.action())
                .param("targetType", entry.targetType())
                .param("targetId", entry.targetId())
                .param("details", json.writeValueAsString(entry.details()))
                .param("client", currentClientAddress())
                .update();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AuditRecord> forOrganization(UUID organizationId, String actionPrefix, int page, int size) {
        String prefix = actionPrefix == null || actionPrefix.isBlank() ? "" : actionPrefix.strip();
        long total = jdbc.sql("SELECT count(*) FROM audit_event WHERE organization_id = :org AND action LIKE :prefix")
                .param("org", organizationId)
                .param("prefix", escapeLike(prefix) + "%")
                .query(Long.class).single();
        List<AuditRecord> items = jdbc.sql("""
                SELECT id, organization_id, actor_id, action, target_type, target_id, details::text AS details,
                       client_address, occurred_at
                  FROM audit_event
                 WHERE organization_id = :org AND action LIKE :prefix
                 ORDER BY occurred_at DESC, id DESC
                 LIMIT :limit OFFSET :offset
                """)
                .param("org", organizationId)
                .param("prefix", escapeLike(prefix) + "%")
                .param("limit", size)
                .param("offset", (long) page * size)
                .query((rs, n) -> new AuditRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("organization_id", UUID.class),
                        rs.getObject("actor_id", UUID.class),
                        rs.getString("action"),
                        rs.getString("target_type"),
                        rs.getString("target_id"),
                        json.readValue(rs.getString("details"), MAP),
                        rs.getString("client_address"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant()))
                .list();
        return new PageResponse<>(items, page, size, total);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String currentClientAddress() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return request.getRemoteAddr();
        }
        return null;
    }
}
