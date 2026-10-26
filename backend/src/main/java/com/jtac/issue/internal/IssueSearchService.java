package com.jtac.issue.internal;

import com.jtac.field.FieldCatalog;
import com.jtac.field.FieldDefinition;
import com.jtac.field.FieldType;
import com.jtac.issue.IssueFilter;
import com.jtac.issue.IssueSearch;
import com.jtac.issue.IssueSummary;
import com.jtac.issue.Priority;
import com.jtac.organization.OrgContext;
import com.jtac.project.ProjectAccess;
import com.jtac.project.ProjectContext;
import com.jtac.shared.web.ApiErrors;
import com.jtac.shared.web.PageResponse;
import com.jtac.workflow.Status;
import com.jtac.workflow.StatusCategory;
import com.jtac.workflow.Workflow;
import com.jtac.workflow.WorkflowCatalog;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds parameterized SQL for {@link IssueFilter}s over the caller's visible projects.
 * For every ruleable field used in a filter, sort or grouping, the query only considers
 * issues whose (project, status) make that field visible to the caller.
 */
@Service
class IssueSearchService implements IssueSearch {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> LIST = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final ProjectAccess projects;
    private final WorkflowCatalog workflows;
    private final FieldCatalog fieldCatalog;

    IssueSearchService(JdbcClient jdbc, JsonMapper json, ProjectAccess projects, WorkflowCatalog workflows,
            FieldCatalog fieldCatalog) {
        this.jdbc = jdbc;
        this.json = json;
        this.projects = projects;
        this.workflows = workflows;
        this.fieldCatalog = fieldCatalog;
    }

    /** Per-project data needed to evaluate field rules. */
    record Scope(ProjectContext ctx, Workflow workflow, List<FieldDefinition> fields, Map<UUID, FieldGuard> guards) {
        FieldGuard guard(UUID statusId) {
            return guards.computeIfAbsent(statusId, s -> FieldGuard.of(workflow, s, ctx.roleIds(), fields));
        }
    }

    /** Accumulates SQL fragments and named parameters. */
    static final class Query {
        final List<String> where = new ArrayList<>();
        final Map<String, Object> params = new HashMap<>();
        private int counter;

        String param(Object value) {
            String name = "p" + counter++;
            params.put(name, value);
            return ":" + name;
        }
    }

    // --- public API ----------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public PageResponse<IssueSummary> search(OrgContext org, IssueFilter filter, int page, int size) {
        Map<UUID, Scope> scopes = scopes(org, filter);
        if (scopes.isEmpty()) {
            return new PageResponse<>(List.of(), page, size, 0);
        }
        Query q = where(org, filter, scopes);
        boolean needProject = (filter.text() != null && !filter.text().isBlank()) || "key".equals(filter.sort());
        boolean needStatus = notEmpty(filter.categories()) || "status".equals(filter.sort());
        String from = " FROM issue i "
                + (needProject ? "JOIN project p ON p.id = i.project_id " : "")
                + (needStatus ? "JOIN status s ON s.id = i.status_id " : "");
        String whereSql = " WHERE " + String.join(" AND ", q.where);
        long total = jdbc.sql("SELECT count(*)" + from + whereSql).params(q.params).query(Long.class).single();
        if (total == 0) {
            return new PageResponse<>(List.of(), page, size, 0);
        }
        // Phase 1: only the ordered page of ids (cheap: no per-row sub-queries, even for deep pages).
        String order = orderBy(filter, scopes, q);
        List<UUID> ids = jdbc.sql("SELECT i.id" + from + whereSql + " ORDER BY " + order + " LIMIT " + q.param(size)
                        + " OFFSET " + q.param((long) page * size))
                .params(q.params).query(UUID.class).list();
        if (ids.isEmpty()) {
            return new PageResponse<>(List.of(), page, size, total);
        }
        // Phase 2: details for exactly those rows, returned in phase-1 order.
        String sql = """
                SELECT i.id, i.project_id, p.key AS project_key, i.number, i.title, i.type_id, t.name AS type_name,
                       i.status_id, s.name AS status_name, s.category AS status_category, i.priority,
                       i.assignee_id, ua.display_name AS assignee_name, ua.handle AS assignee_handle,
                       i.reporter_id, ur.display_name AS reporter_name, ur.handle AS reporter_handle,
                       i.start_date, i.due_date, i.estimate, i.milestone_id, m.name AS milestone_name,
                       (SELECT pp.key || '-' || pi.number FROM issue pi JOIN project pp ON pp.id = pi.project_id
                         WHERE pi.id = i.parent_id) AS parent_key,
                       i.rank, i.custom_values::text AS custom_values, i.created_at, i.updated_at, i.resolved_at,
                       coalesce((SELECT json_agg(json_build_object('id', l.id, 'name', l.name, 'color', l.color) ORDER BY lower(l.name))
                                   FROM issue_label il JOIN label l ON l.id = il.label_id WHERE il.issue_id = i.id), '[]')::text AS labels,
                       (SELECT count(*) FROM issue c WHERE c.parent_id = i.id) AS subtasks,
                       (SELECT count(*) FROM issue c JOIN status cs ON cs.id = c.status_id
                         WHERE c.parent_id = i.id AND cs.category IN ('DONE', 'CANCELED')) AS subtasks_done
                  FROM issue i
                  JOIN project p ON p.id = i.project_id
                  JOIN status s ON s.id = i.status_id
                  JOIN issue_type t ON t.id = i.type_id
                  LEFT JOIN user_account ua ON ua.id = i.assignee_id
                  LEFT JOIN user_account ur ON ur.id = i.reporter_id
                  LEFT JOIN milestone m ON m.id = i.milestone_id
                 WHERE i.organization_id = :org AND i.id IN (:ids)
                """;
        Map<UUID, IssueSummary> byId = new HashMap<>();
        jdbc.sql(sql).param("org", org.organizationId()).param("ids", ids)
                .query((rs, n) -> summary(rs, scopes)).list()
                .forEach(summary -> byId.put(summary.id(), summary));
        List<IssueSummary> items = ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
        return new PageResponse<>(items, page, size, total);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Bucket> countBy(OrgContext org, IssueFilter filter, String groupBy) {
        if (!GROUPS.contains(groupBy)) {
            throw ApiErrors.invalid("groupBy", "Use one of " + GROUPS + ".");
        }
        Map<UUID, Scope> scopes = scopes(org, filter);
        if (scopes.isEmpty()) {
            return List.of();
        }
        Query q = where(org, filter, scopes);
        String estimate = "CASE WHEN " + visible("estimate", scopes, q) + " THEN i.estimate END";
        String select;
        String joins = "";
        String group;
        switch (groupBy) {
            case "status" -> {
                select = "s.id::text AS k, s.name AS label";
                group = "s.id, s.name, s.position";
            }
            case "category" -> {
                select = "s.category AS k, s.category AS label";
                group = "s.category";
            }
            case "project" -> {
                select = "p.id::text AS k, p.name AS label";
                group = "p.id, p.name";
            }
            case "type" -> {
                joins = " JOIN issue_type t ON t.id = i.type_id ";
                select = "t.id::text AS k, t.name AS label";
                group = "t.id, t.name";
            }
            case "priority" -> {
                String v = visible("priority", scopes, q);
                select = "CASE WHEN " + v + " THEN i.priority ELSE 'HIDDEN' END AS k, CASE WHEN " + v + " THEN i.priority ELSE 'Hidden' END AS label";
                group = "1, 2";
            }
            case "assignee" -> {
                String v = visible("assignee", scopes, q);
                joins = " LEFT JOIN user_account u ON u.id = i.assignee_id ";
                select = "CASE WHEN " + v + " THEN i.assignee_id::text ELSE 'hidden' END AS k, "
                        + "CASE WHEN " + v + " THEN coalesce(u.display_name, 'Unassigned') ELSE 'Hidden' END AS label";
                group = "1, 2";
            }
            case "milestone" -> {
                String v = visible("milestone", scopes, q);
                joins = " LEFT JOIN milestone m ON m.id = i.milestone_id ";
                select = "CASE WHEN " + v + " THEN i.milestone_id::text ELSE 'hidden' END AS k, "
                        + "CASE WHEN " + v + " THEN coalesce(m.name, 'No milestone') ELSE 'Hidden' END AS label";
                group = "1, 2";
            }
            case "label" -> {
                String v = visible("labels", scopes, q);
                joins = " LEFT JOIN issue_label il ON il.issue_id = i.id AND " + v + " LEFT JOIN label l ON l.id = il.label_id ";
                select = "l.id::text AS k, coalesce(l.name, 'No label') AS label";
                group = "l.id, l.name";
            }
            default -> throw new IllegalStateException(groupBy);
        }
        String sql = "SELECT " + select + ", count(DISTINCT i.id) AS n, coalesce(sum(" + estimate + "), 0) AS est"
                + " FROM issue i JOIN project p ON p.id = i.project_id JOIN status s ON s.id = i.status_id " + joins
                + " WHERE " + String.join(" AND ", q.where) + " GROUP BY " + group + " ORDER BY n DESC, label";
        return jdbc.sql(sql).params(q.params)
                .query((rs, n) -> new Bucket(rs.getString("k"), rs.getString("label"), rs.getLong("n"),
                        rs.getBigDecimal("est").doubleValue()))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DayCount> createdVsResolved(OrgContext org, IssueFilter filter, LocalDate from, LocalDate to) {
        if (from.isAfter(to) || from.plusDays(400).isBefore(to)) {
            throw ApiErrors.invalid("from", "Use a range of at most 400 days.");
        }
        Map<UUID, Scope> scopes = scopes(org, filter);
        if (scopes.isEmpty()) {
            return from.datesUntil(to.plusDays(1)).map(d -> new DayCount(d, 0, 0)).toList();
        }
        Query q = where(org, filter, scopes);
        String base = " FROM issue i JOIN project p ON p.id = i.project_id JOIN status s ON s.id = i.status_id WHERE "
                + String.join(" AND ", q.where);
        String fromParam = q.param(from);
        String toParam = q.param(to);
        String sql = "WITH matching AS (SELECT i.created_at, i.resolved_at" + base + ") "
                + "SELECT d::date AS day, "
                + "(SELECT count(*) FROM matching WHERE created_at::date = d::date) AS created, "
                + "(SELECT count(*) FROM matching WHERE resolved_at::date = d::date) AS resolved "
                + "FROM generate_series(CAST(" + fromParam + " AS date), CAST(" + toParam + " AS date), interval '1 day') d ORDER BY d";
        return jdbc.sql(sql).params(q.params)
                .query((rs, n) -> new DayCount(rs.getObject("day", LocalDate.class), rs.getLong("created"), rs.getLong("resolved")))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Dependency> dependencies(OrgContext org, Collection<UUID> issueIds) {
        if (issueIds.isEmpty()) {
            return List.of();
        }
        List<UUID> projectIds = projects.visible(org).stream().map(ProjectContext::projectId).toList();
        if (projectIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT a.id AS blocker_id, pa.key || '-' || a.number AS blocker_key,
                       b.id AS blocked_id, pb.key || '-' || b.number AS blocked_key
                  FROM issue_link l
                  JOIN issue a ON a.id = l.source_id JOIN project pa ON pa.id = a.project_id
                  JOIN issue b ON b.id = l.target_id JOIN project pb ON pb.id = b.project_id
                 WHERE l.organization_id = :org AND l.type = 'BLOCKS'
                   AND l.source_id IN (:ids) AND l.target_id IN (:ids)
                   AND a.project_id IN (:projects) AND b.project_id IN (:projects)
                 ORDER BY blocker_key, blocked_key
                """)
                .param("org", org.organizationId()).param("ids", List.copyOf(issueIds)).param("projects", projectIds)
                .query((rs, n) -> new Dependency(rs.getObject("blocker_id", UUID.class), rs.getString("blocker_key"),
                        rs.getObject("blocked_id", UUID.class), rs.getString("blocked_key")))
                .list();
    }

    // --- scopes and WHERE ----------------------------------------------------------------

    Map<UUID, Scope> scopes(OrgContext org, IssueFilter filter) {
        Set<String> keys = filter.projectKeys() == null || filter.projectKeys().isEmpty() ? null
                : filter.projectKeys().stream().map(k -> k.toUpperCase(java.util.Locale.ROOT)).collect(Collectors.toSet());
        Map<UUID, Scope> scopes = new LinkedHashMap<>();
        for (ProjectContext ctx : projects.visible(org)) {
            if (keys == null || keys.contains(ctx.project().key())) {
                scopes.put(ctx.projectId(), new Scope(ctx, workflows.published(ctx.projectId()),
                        fieldCatalog.fields(ctx.projectId()), new HashMap<>()));
            }
        }
        return scopes;
    }

    Query where(OrgContext org, IssueFilter f, Map<UUID, Scope> scopes) {
        Query q = new Query();
        q.where.add("i.organization_id = " + q.param(org.organizationId()));
        q.where.add("i.project_id IN (" + q.param(new ArrayList<>(scopes.keySet())) + ")");
        if (f.text() != null && !f.text().isBlank()) {
            String text = f.text().strip();
            if (text.length() > 200) {
                throw ApiErrors.invalid("text", "At most 200 characters.");
            }
            // The indexed vector covers title and description; where the description is hidden
            // from the caller, only the title may match (otherwise matches would reveal it).
            String tsquery = "websearch_to_tsquery('simple', " + q.param(text) + ")";
            q.where.add("((i.search_vector @@ " + tsquery + " AND " + visible("description", scopes, q) + ")"
                    + " OR to_tsvector('simple', i.title) @@ " + tsquery + " OR (p.key || '-' || i.number) = "
                    + q.param(text.toUpperCase(java.util.Locale.ROOT)) + " OR i.title ILIKE " + q.param("%" + escapeLike(text) + "%") + ")");
        }
        if (notEmpty(f.statusIds())) {
            q.where.add("i.status_id IN (" + q.param(f.statusIds()) + ")");
        }
        if (notEmpty(f.categories())) {
            q.where.add("s.category IN (" + q.param(f.categories().stream().map(StatusCategory::name).toList()) + ")");
        }
        if (f.resolved() != null) {
            q.where.add(f.resolved() ? "i.resolved_at IS NOT NULL" : "i.resolved_at IS NULL");
        }
        if (notEmpty(f.typeIds())) {
            q.where.add("i.type_id IN (" + q.param(f.typeIds()) + ")");
        }
        if (notEmpty(f.reporters())) {
            q.where.add("i.reporter_id IN (" + q.param(f.reporters()) + ")");
        }
        if (f.updatedSince() != null) {
            q.where.add("i.updated_at >= " + q.param(f.updatedSince().atOffset(java.time.ZoneOffset.UTC)));
        }
        if (notEmpty(f.assignees())) {
            List<String> parts = new ArrayList<>();
            List<UUID> ids = new ArrayList<>();
            for (String a : f.assignees()) {
                if ("none".equalsIgnoreCase(a)) {
                    parts.add("i.assignee_id IS NULL");
                } else if ("me".equalsIgnoreCase(a)) {
                    ids.add(org.actorId());
                } else {
                    ids.add(parseUuid("assignees", a));
                }
            }
            if (!ids.isEmpty()) {
                parts.add("i.assignee_id IN (" + q.param(ids) + ")");
            }
            q.where.add("(" + String.join(" OR ", parts) + ") AND " + visible("assignee", scopes, q));
        }
        if (notEmpty(f.priorities())) {
            q.where.add("i.priority IN (" + q.param(f.priorities().stream().map(Priority::name).toList()) + ") AND "
                    + visible("priority", scopes, q));
        }
        if (notEmpty(f.labelIds())) {
            q.where.add("EXISTS (SELECT 1 FROM issue_label il WHERE il.issue_id = i.id AND il.label_id IN (" + q.param(f.labelIds())
                    + ")) AND " + visible("labels", scopes, q));
        }
        if (notEmpty(f.milestones())) {
            List<String> parts = new ArrayList<>();
            List<UUID> ids = new ArrayList<>();
            for (String m : f.milestones()) {
                if ("none".equalsIgnoreCase(m)) {
                    parts.add("i.milestone_id IS NULL");
                } else {
                    ids.add(parseUuid("milestones", m));
                }
            }
            if (!ids.isEmpty()) {
                parts.add("i.milestone_id IN (" + q.param(ids) + ")");
            }
            q.where.add("(" + String.join(" OR ", parts) + ") AND " + visible("milestone", scopes, q));
        }
        if (f.dueFrom() != null || f.dueTo() != null) {
            String cond = "i.due_date IS NOT NULL";
            if (f.dueFrom() != null) {
                cond += " AND i.due_date >= " + q.param(f.dueFrom());
            }
            if (f.dueTo() != null) {
                cond += " AND i.due_date <= " + q.param(f.dueTo());
            }
            q.where.add(cond + " AND " + visible("dueDate", scopes, q));
        }
        if (f.parentKey() != null && !f.parentKey().isBlank()) {
            var parts = IssueStore.parseKey(f.parentKey()).orElseThrow(() -> ApiErrors.invalid("parentKey", "Invalid issue key."));
            q.where.add("i.parent_id = (SELECT pi.id FROM issue pi JOIN project pp ON pp.id = pi.project_id WHERE pp.organization_id = "
                    + q.param(org.organizationId()) + " AND pp.key = " + q.param(parts.projectKey()) + " AND pi.number = "
                    + q.param(parts.number()) + ") AND " + visible("parent", scopes, q));
        }
        if (f.subtasks() != null) {
            q.where.add(f.subtasks() ? "(i.parent_id IS NOT NULL AND " + visible("parent", scopes, q) + ")" : "i.parent_id IS NULL");
        }
        if (f.custom() != null) {
            for (IssueFilter.CustomCondition condition : f.custom()) {
                q.where.add(custom(condition, scopes, q));
            }
        }
        return q;
    }

    /** SQL condition: the ruleable {@code field} is visible to the caller for the issue's project and status. */
    String visible(String field, Map<UUID, Scope> scopes, Query q) {
        List<String> parts = new ArrayList<>();
        for (Scope scope : scopes.values()) {
            List<UUID> visibleStatuses = new ArrayList<>();
            List<Status> statuses = scope.workflow().statusesById().values().stream().toList();
            for (Status status : statuses) {
                if (scope.guard(status.id()).visible(field)) {
                    visibleStatuses.add(status.id());
                }
            }
            if (visibleStatuses.size() == statuses.size()) {
                parts.add("i.project_id = " + q.param(scope.ctx().projectId()));
            } else if (!visibleStatuses.isEmpty()) {
                parts.add("(i.project_id = " + q.param(scope.ctx().projectId()) + " AND i.status_id IN (" + q.param(visibleStatuses) + "))");
            }
        }
        return parts.isEmpty() ? "FALSE" : "(" + String.join(" OR ", parts) + ")";
    }

    private String custom(IssueFilter.CustomCondition condition, Map<UUID, Scope> scopes, Query q) {
        if (condition.field() == null || condition.op() == null) {
            throw ApiErrors.invalid("custom", "Each condition needs a field and an operator.");
        }
        List<String> parts = new ArrayList<>();
        for (Scope scope : scopes.values()) {
            FieldDefinition def = scope.fields().stream().filter(d -> d.key().equals(condition.field())).findFirst().orElse(null);
            if (def == null) {
                continue;
            }
            Map<UUID, Scope> single = Map.of(scope.ctx().projectId(), scope);
            String visibleCond = visible(def.ruleKey(), single, q);
            parts.add("(" + visibleCond + " AND " + predicate(def, condition, q) + ")");
        }
        return parts.isEmpty() ? "FALSE" : "(" + String.join(" OR ", parts) + ")";
    }

    private String predicate(FieldDefinition def, IssueFilter.CustomCondition c, Query q) {
        String key = q.param(def.key());
        String text = "(i.custom_values ->> " + key + ")";
        String typed = switch (def.type()) {
            case NUMBER -> "CAST(" + text + " AS numeric)";
            case DATE -> "CAST(" + text + " AS date)";
            case CHECKBOX -> "CAST(" + text + " AS boolean)";
            default -> text;
        };
        Object value = c.value();
        return switch (c.op()) {
            case "empty" -> "NOT jsonb_exists(i.custom_values, " + key + ")";
            case "notEmpty" -> "jsonb_exists(i.custom_values, " + key + ")";
            case "eq", "ne" -> {
                String cmp = def.type() == FieldType.MULTI_SELECT
                        ? "jsonb_exists(i.custom_values -> " + key + ", " + q.param(String.valueOf(value)) + ")"
                        : typed + " = " + typedParam(def.type(), value, q);
                yield "eq".equals(c.op()) ? cmp : "NOT coalesce(" + cmp + ", false)";
            }
            case "in" -> {
                if (!(value instanceof List<?> values) || values.isEmpty()) {
                    throw ApiErrors.invalid("custom", "'in' needs a non-empty list.");
                }
                List<String> strings = values.stream().map(String::valueOf).toList();
                yield def.type() == FieldType.MULTI_SELECT
                        ? "jsonb_exists_any(i.custom_values -> " + key + ", CAST(" + q.param(strings.toArray(String[]::new)) + " AS text[]))"
                        : text + " IN (" + q.param(strings) + ")";
            }
            case "contains" -> text + " ILIKE " + q.param("%" + escapeLike(String.valueOf(value)) + "%");
            case "gt", "gte", "lt", "lte" -> {
                if (def.type() != FieldType.NUMBER && def.type() != FieldType.DATE) {
                    throw ApiErrors.invalid("custom", "Comparisons need a number or date field.");
                }
                String op = switch (c.op()) {
                    case "gt" -> ">";
                    case "gte" -> ">=";
                    case "lt" -> "<";
                    default -> "<=";
                };
                yield typed + " " + op + " " + typedParam(def.type(), value, q);
            }
            default -> throw ApiErrors.invalid("custom", "Unknown operator '" + c.op() + "'.");
        };
    }

    private static String typedParam(FieldType type, Object value, Query q) {
        try {
            return switch (type) {
                case NUMBER -> q.param(new BigDecimal(String.valueOf(value)));
                case DATE -> q.param(LocalDate.parse(String.valueOf(value)));
                case CHECKBOX -> q.param(Boolean.parseBoolean(String.valueOf(value)));
                default -> q.param(String.valueOf(value));
            };
        } catch (RuntimeException e) {
            throw ApiErrors.invalid("custom", "Invalid value for this field type.");
        }
    }

    private String orderBy(IssueFilter f, Map<UUID, Scope> scopes, Query q) {
        String sort = f.sort() == null ? "rank" : f.sort();
        if (!IssueFilter.SORT_FIELDS.contains(sort)) {
            throw ApiErrors.invalid("sort", "Use one of " + IssueFilter.SORT_FIELDS + ".");
        }
        boolean desc = "desc".equalsIgnoreCase(f.direction());
        String dir = desc ? " DESC" : " ASC";
        String expr = switch (sort) {
            case "created" -> "i.created_at";
            case "updated" -> "i.updated_at";
            case "key" -> "p.key" + dir + ", i.number";
            case "title" -> "lower(i.title)";
            case "status" -> "s.position";
            case "priority" -> "CASE WHEN " + visible("priority", scopes, q)
                    + " THEN array_position(ARRAY['URGENT','HIGH','MEDIUM','LOW','NONE'], i.priority) END";
            case "dueDate" -> "CASE WHEN " + visible("dueDate", scopes, q) + " THEN i.due_date END";
            case "startDate" -> "CASE WHEN " + visible("startDate", scopes, q) + " THEN i.start_date END";
            case "estimate" -> "CASE WHEN " + visible("estimate", scopes, q) + " THEN i.estimate END";
            case "assignee" -> "CASE WHEN " + visible("assignee", scopes, q)
                    + " THEN (SELECT lower(u.display_name) FROM user_account u WHERE u.id = i.assignee_id) END";
            default -> "i.rank";
        };
        return expr + dir + " NULLS LAST, i.id";
    }

    // --- mapping -------------------------------------------------------------------------

    private IssueSummary summary(ResultSet rs, Map<UUID, Scope> scopes) throws SQLException {
        UUID projectId = rs.getObject("project_id", UUID.class);
        UUID statusId = rs.getObject("status_id", UUID.class);
        Scope scope = scopes.get(projectId);
        FieldGuard guard = scope.guard(statusId);
        List<String> hidden = guard.access().entrySet().stream().filter(e -> !e.getValue().visible())
                .map(Map.Entry::getKey).sorted().toList();
        Map<String, Object> customAll = json.readValue(rs.getString("custom_values"), MAP);
        Map<String, Object> custom = new LinkedHashMap<>();
        for (FieldDefinition def : scope.fields()) {
            if (guard.visible(def.ruleKey()) && customAll.containsKey(def.key())) {
                custom.put(def.key(), customAll.get(def.key()));
            }
        }
        List<IssueSummary.LabelRef> labels = null;
        if (guard.visible("labels")) {
            labels = json.readValue(rs.getString("labels"), LIST).stream()
                    .map(l -> new IssueSummary.LabelRef(UUID.fromString((String) l.get("id")), (String) l.get("name"),
                            (String) l.get("color")))
                    .toList();
        }
        UUID assigneeId = rs.getObject("assignee_id", UUID.class);
        UUID milestoneId = rs.getObject("milestone_id", UUID.class);
        OffsetDateTime resolved = rs.getObject("resolved_at", OffsetDateTime.class);
        return new IssueSummary(rs.getObject("id", UUID.class), rs.getString("project_key") + "-" + rs.getLong("number"),
                rs.getString("project_key"), projectId, rs.getString("title"),
                new IssueSummary.Ref(rs.getObject("type_id", UUID.class), rs.getString("type_name")),
                new IssueSummary.StatusRef(statusId, rs.getString("status_name"), rs.getString("status_category")),
                guard.visible("priority") ? Priority.valueOf(rs.getString("priority")) : null,
                guard.visible("assignee") && assigneeId != null
                        ? new IssueSummary.Person(assigneeId, rs.getString("assignee_name"), rs.getString("assignee_handle")) : null,
                new IssueSummary.Person(rs.getObject("reporter_id", UUID.class), rs.getString("reporter_name"),
                        rs.getString("reporter_handle")),
                labels,
                guard.visible("startDate") ? rs.getObject("start_date", LocalDate.class) : null,
                guard.visible("dueDate") ? rs.getObject("due_date", LocalDate.class) : null,
                guard.visible("estimate") ? rs.getBigDecimal("estimate") : null,
                guard.visible("milestone") && milestoneId != null ? new IssueSummary.Ref(milestoneId, rs.getString("milestone_name")) : null,
                guard.visible("parent") ? rs.getString("parent_key") : null,
                rs.getLong("rank"), custom, rs.getInt("subtasks"), rs.getInt("subtasks_done"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                resolved == null ? null : resolved.toInstant(), hidden);
    }

    private static boolean notEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }

    private static UUID parseUuid(String field, String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw ApiErrors.invalid(field, "Invalid identifier '" + value + "'.");
        }
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
