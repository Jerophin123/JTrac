package com.jtac.issue.internal;

import com.jtac.issue.Priority;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Loads issue rows. Callers are responsible for authorization. */
@Component
class IssueStore {

    static final Pattern ISSUE_KEY = Pattern.compile("^([A-Z][A-Z0-9]{1,9})-([1-9][0-9]{0,15})$");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    static final String SELECT = """
            SELECT i.id, i.organization_id, i.project_id, p.key AS project_key, i.number, i.type_id, i.title, i.description,
                   i.status_id, i.priority, i.assignee_id, i.reporter_id, i.parent_id, i.milestone_id, i.start_date,
                   i.due_date, i.estimate, i.rank, i.custom_values::text AS custom_values, i.created_at, i.updated_at,
                   i.resolved_at, i.version,
                   ARRAY(SELECT il.label_id FROM issue_label il WHERE il.issue_id = i.id) AS label_ids
              FROM issue i JOIN project p ON p.id = i.project_id
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    IssueStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    record KeyParts(String projectKey, long number) {
    }

    static Optional<KeyParts> parseKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        Matcher m = ISSUE_KEY.matcher(key.strip().toUpperCase(java.util.Locale.ROOT));
        return m.matches() ? Optional.of(new KeyParts(m.group(1), Long.parseLong(m.group(2)))) : Optional.empty();
    }

    Optional<IssueRecord> byKey(UUID organizationId, String key) {
        return parseKey(key).flatMap(parts -> jdbc.sql(SELECT + " WHERE p.organization_id = :org AND p.key = :key AND i.number = :n")
                .param("org", organizationId).param("key", parts.projectKey()).param("n", parts.number())
                .query(this::map).optional());
    }

    Optional<IssueRecord> byId(UUID organizationId, UUID id) {
        return jdbc.sql(SELECT + " WHERE i.organization_id = :org AND i.id = :id")
                .param("org", organizationId).param("id", id).query(this::map).optional();
    }

    /** Locks the row for the rest of the transaction (serializes concurrent writers). */
    Optional<IssueRecord> lock(UUID organizationId, UUID id) {
        jdbc.sql("SELECT id FROM issue WHERE organization_id = :org AND id = :id FOR UPDATE")
                .param("org", organizationId).param("id", id).query(UUID.class).optional();
        return byId(organizationId, id);
    }

    List<IssueRecord> children(UUID issueId) {
        return jdbc.sql(SELECT + " WHERE i.parent_id = :id ORDER BY i.rank").param("id", issueId).query(this::map).list();
    }

    IssueRecord map(ResultSet rs, int rowNum) throws SQLException {
        List<UUID> labels = new ArrayList<>();
        for (Object value : (Object[]) rs.getArray("label_ids").getArray()) {
            labels.add((UUID) value);
        }
        OffsetDateTime resolved = rs.getObject("resolved_at", OffsetDateTime.class);
        return new IssueRecord(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getString("project_key"), rs.getLong("number"),
                rs.getObject("type_id", UUID.class), rs.getString("title"), rs.getString("description"),
                rs.getObject("status_id", UUID.class), Priority.valueOf(rs.getString("priority")),
                rs.getObject("assignee_id", UUID.class), rs.getObject("reporter_id", UUID.class),
                rs.getObject("parent_id", UUID.class), rs.getObject("milestone_id", UUID.class),
                rs.getObject("start_date", LocalDate.class), rs.getObject("due_date", LocalDate.class),
                rs.getObject("estimate", BigDecimal.class), rs.getLong("rank"),
                json.readValue(rs.getString("custom_values"), MAP), List.copyOf(labels),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                resolved == null ? null : resolved.toInstant(), rs.getLong("version"));
    }
}
