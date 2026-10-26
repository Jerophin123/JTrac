package com.jtac.issue.internal;

import com.jtac.field.FieldDefinition;
import com.jtac.field.FieldType;
import com.jtac.identity.UserDirectory;
import com.jtac.identity.UserSummary;
import com.jtac.project.ProjectCatalog;
import com.jtac.project.ProjectContext;
import com.jtac.shared.Ids;
import com.jtac.workflow.Workflow;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the append-only activity history. Values are stored as display snapshots
 * (e.g. {@code {"id": ..., "name": "In Review"}}) so history stays readable after renames.
 */
@Component
class ActivityWriter {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final ProjectCatalog catalog;
    private final UserDirectory users;

    ActivityWriter(JdbcClient jdbc, JsonMapper json, ProjectCatalog catalog, UserDirectory users) {
        this.jdbc = jdbc;
        this.json = json;
        this.catalog = catalog;
        this.users = users;
    }

    void write(UUID organizationId, UUID issueId, UUID actorId, String kind, String field, Object oldValue, Object newValue) {
        jdbc.sql("""
                INSERT INTO issue_activity (id, organization_id, issue_id, actor_id, kind, field, old_value, new_value)
                VALUES (:id, :org, :issue, :actor, :kind, :field, CAST(:old AS jsonb), CAST(:new AS jsonb))
                """)
                .param("id", Ids.newId()).param("org", organizationId).param("issue", issueId).param("actor", actorId)
                .param("kind", kind).param("field", field)
                .param("old", oldValue == null ? null : json.writeValueAsString(oldValue))
                .param("new", newValue == null ? null : json.writeValueAsString(newValue))
                .update();
    }

    void writeChanges(ProjectContext ctx, IssueRecord before, IssueRecord after, Collection<String> changed,
            List<FieldDefinition> defs, Workflow workflow) {
        for (String field : changed) {
            Object oldValue = display(ctx, before, field, defs, workflow);
            Object newValue = display(ctx, after, field, defs, workflow);
            String kind = "status".equals(field) ? "TRANSITIONED" : "FIELD_CHANGED";
            write(ctx.organizationId(), after.id(), ctx.actorId(), kind, field, oldValue, newValue);
        }
    }

    private Object display(ProjectContext ctx, IssueRecord issue, String field, List<FieldDefinition> defs, Workflow workflow) {
        return switch (field) {
            case "title" -> issue.title();
            case "description" -> issue.description() == null ? null : Map.of("length", issue.description().length());
            case "status" -> workflow.status(issue.statusId()).map(s -> ref(s.id(), s.name())).orElse(null);
            case "priority" -> issue.priority().name();
            case "assignee" -> person(issue.assigneeId());
            case "type" -> catalog.issueTypes(ctx.projectId()).stream().filter(t -> t.id().equals(issue.typeId())).findFirst()
                    .map(t -> ref(t.id(), t.name())).orElse(null);
            case "milestone" -> issue.milestoneId() == null ? null : catalog.milestones(ctx.projectId()).stream()
                    .filter(m -> m.id().equals(issue.milestoneId())).findFirst().map(m -> ref(m.id(), m.name())).orElse(null);
            case "labels" -> {
                List<Map<String, Object>> labels = new ArrayList<>();
                catalog.labels(ctx.projectId()).stream().filter(l -> issue.labelIds().contains(l.id()))
                        .forEach(l -> labels.add(ref(l.id(), l.name())));
                yield labels;
            }
            case "parent" -> issue.parentId() == null ? null : jdbc.sql("""
                    SELECT p.key || '-' || i.number FROM issue i JOIN project p ON p.id = i.project_id WHERE i.id = :id
                    """).param("id", issue.parentId()).query(String.class).optional().orElse(null);
            case "startDate" -> issue.startDate() == null ? null : issue.startDate().toString();
            case "dueDate" -> issue.dueDate() == null ? null : issue.dueDate().toString();
            case "estimate" -> issue.estimate();
            default -> customDisplay(issue, field, defs);
        };
    }

    private Object customDisplay(IssueRecord issue, String field, List<FieldDefinition> defs) {
        if (!field.startsWith("cf:")) {
            return null;
        }
        String key = field.substring(3);
        FieldDefinition def = defs.stream().filter(f -> f.key().equals(key)).findFirst().orElse(null);
        Object value = issue.customValues().get(key);
        if (def == null || value == null) {
            return value;
        }
        if (def.type() == FieldType.SELECT) {
            return optionLabel(def, value.toString());
        }
        if (def.type() == FieldType.MULTI_SELECT && value instanceof List<?> ids) {
            return ids.stream().map(id -> optionLabel(def, id.toString())).toList();
        }
        if (def.type() == FieldType.USER) {
            return person(UUID.fromString(value.toString()));
        }
        if (def.type() == FieldType.LONG_TEXT) {
            return Map.of("length", value.toString().length());
        }
        return value instanceof BigDecimal n ? n.toPlainString() : value;
    }

    private static String optionLabel(FieldDefinition def, String optionId) {
        return def.options().stream().filter(o -> o.id().equals(optionId)).findFirst()
                .map(FieldDefinition.Option::label).orElse("(removed option)");
    }

    private Map<String, Object> person(UUID userId) {
        if (userId == null) {
            return null;
        }
        UserSummary user = users.find(userId).orElse(null);
        return ref(userId, user == null ? "Unknown user" : user.displayName());
    }

    private static Map<String, Object> ref(UUID id, String name) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id.toString());
        value.put("name", name);
        return value;
    }
}
