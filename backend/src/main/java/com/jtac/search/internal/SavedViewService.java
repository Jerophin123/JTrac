package com.jtac.search.internal;

import com.jtac.issue.IssueFilter;
import com.jtac.organization.OrgContext;
import com.jtac.project.ProjectAccess;
import com.jtac.project.ProjectContext;
import com.jtac.shared.Ids;
import com.jtac.shared.web.ApiErrors;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Saved views. A view stores an {@link IssueFilter} (validated on save) and display settings
 * (columns, grouping, density). Private views are visible to their owner only; shared views
 * to every member who can browse the view's project (or every member for cross-project
 * views). Owners edit their views; organization admins may also edit or delete shared ones.
 * Running a view always re-applies the viewer's own permissions.
 */
@Service
class SavedViewService {

    static final Set<String> KINDS = Set.of("LIST", "BOARD", "TIMELINE");
    private static final int MAX_DISPLAY_JSON = 8_000;
    private static final int MAX_VIEWS_PER_OWNER = 200;

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final ProjectAccess projects;

    SavedViewService(JdbcClient jdbc, JsonMapper json, ProjectAccess projects) {
        this.jdbc = jdbc;
        this.json = json;
        this.projects = projects;
    }

    record SavedView(UUID id, String name, String kind, String projectKey, IssueFilter query, Map<String, Object> display,
            boolean shared, UUID ownerId, String ownerName, boolean editable, Instant updatedAt, long version) {
    }

    record ViewInput(String name, String kind, String projectKey, IssueFilter query, Map<String, Object> display,
            Boolean shared) {
    }

    @Transactional(readOnly = true)
    List<SavedView> list(OrgContext ctx, String projectKey) {
        UUID projectId = projectKey == null ? null : projects.require(ctx, projectKey).projectId();
        List<Row> rows = jdbc.sql("""
                SELECT v.*, p.key AS project_key, u.display_name AS owner_name
                  FROM saved_view v
                  LEFT JOIN project p ON p.id = v.project_id
                  JOIN user_account u ON u.id = v.owner_id
                 WHERE v.organization_id = :org AND (v.owner_id = :user OR v.shared)
                   AND (CAST(:project AS uuid) IS NULL OR v.project_id = :project)
                 ORDER BY lower(v.name), v.id
                """)
                .param("org", ctx.organizationId()).param("user", ctx.actorId()).param("project", projectId)
                .query(this::row).list();
        Set<UUID> browsable = projects.visible(ctx, rows.stream().map(Row::projectId).filter(id -> id != null).toList())
                .keySet();
        return rows.stream()
                .filter(r -> r.projectId() == null || browsable.contains(r.projectId()))
                .map(r -> r.toView(ctx))
                .toList();
    }

    @Transactional(readOnly = true)
    SavedView get(OrgContext ctx, UUID id) {
        return load(ctx, id).toView(ctx);
    }

    @Transactional
    SavedView create(OrgContext ctx, ViewInput input) {
        Validated v = validate(ctx, input, null);
        long count = jdbc.sql("SELECT count(*) FROM saved_view WHERE organization_id = :org AND owner_id = :user")
                .param("org", ctx.organizationId()).param("user", ctx.actorId()).query(Long.class).single();
        if (count >= MAX_VIEWS_PER_OWNER) {
            throw ApiErrors.conflict("You have reached the limit of " + MAX_VIEWS_PER_OWNER + " saved views.");
        }
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO saved_view (id, organization_id, project_id, owner_id, name, kind, query, display, shared)
                VALUES (:id, :org, :project, :owner, :name, :kind, CAST(:query AS jsonb), CAST(:display AS jsonb), :shared)
                """)
                .param("id", id).param("org", ctx.organizationId()).param("project", v.projectId())
                .param("owner", ctx.actorId()).param("name", v.name()).param("kind", v.kind())
                .param("query", v.queryJson()).param("display", v.displayJson()).param("shared", v.shared()).update();
        return get(ctx, id);
    }

    @Transactional
    SavedView update(OrgContext ctx, UUID id, long version, ViewInput input) {
        Row existing = load(ctx, id);
        requireEditable(ctx, existing);
        Validated v = validate(ctx, input, existing);
        int updated = jdbc.sql("""
                UPDATE saved_view SET name = :name, kind = :kind, project_id = :project, query = CAST(:query AS jsonb),
                       display = CAST(:display AS jsonb), shared = :shared, updated_at = now(), version = version + 1
                 WHERE id = :id AND organization_id = :org AND version = :version
                """)
                .param("name", v.name()).param("kind", v.kind()).param("project", v.projectId())
                .param("query", v.queryJson()).param("display", v.displayJson()).param("shared", v.shared())
                .param("id", id).param("org", ctx.organizationId()).param("version", version).update();
        if (updated == 0) {
            throw new OptimisticLockingFailureException("Saved view was changed by someone else.");
        }
        return get(ctx, id);
    }

    @Transactional
    void delete(OrgContext ctx, UUID id) {
        requireEditable(ctx, load(ctx, id));
        jdbc.sql("DELETE FROM saved_view WHERE id = :id AND organization_id = :org")
                .param("id", id).param("org", ctx.organizationId()).update();
    }

    private void requireEditable(OrgContext ctx, Row row) {
        if (!row.editableBy(ctx)) {
            throw ApiErrors.forbidden("Only the owner of this view (or an organization admin, for shared views) can change it.");
        }
    }

    private Row load(OrgContext ctx, UUID id) {
        Row row = jdbc.sql("""
                SELECT v.*, p.key AS project_key, u.display_name AS owner_name
                  FROM saved_view v
                  LEFT JOIN project p ON p.id = v.project_id
                  JOIN user_account u ON u.id = v.owner_id
                 WHERE v.id = :id AND v.organization_id = :org AND (v.owner_id = :user OR v.shared)
                """)
                .param("id", id).param("org", ctx.organizationId()).param("user", ctx.actorId())
                .query(this::row).optional().orElseThrow(() -> ApiErrors.notFound("Saved view"));
        if (row.projectId() != null && projects.visible(ctx, List.of(row.projectId())).isEmpty()) {
            throw ApiErrors.notFound("Saved view");
        }
        return row;
    }

    private record Validated(String name, String kind, UUID projectId, String queryJson, String displayJson,
            boolean shared) {
    }

    private Validated validate(OrgContext ctx, ViewInput input, Row existing) {
        String name = input.name() == null ? null : input.name().strip();
        if (name == null || name.isEmpty() || name.length() > 80) {
            throw ApiErrors.invalid("name", "Enter a name of 1 to 80 characters.");
        }
        String kind = input.kind() == null ? "LIST" : input.kind();
        if (!KINDS.contains(kind)) {
            throw ApiErrors.invalid("kind", "Choose LIST, BOARD or TIMELINE.");
        }
        UUID projectId = null;
        if (input.projectKey() != null && !input.projectKey().isBlank()) {
            ProjectContext project = projects.require(ctx, input.projectKey());
            projectId = project.projectId();
        }
        if ("BOARD".equals(kind) && projectId == null) {
            throw ApiErrors.invalid("projectKey", "Board views belong to a project.");
        }
        IssueFilter query = input.query() == null ? IssueFilter.empty() : input.query();
        if (query.sort() != null && !IssueFilter.SORT_FIELDS.contains(query.sort())) {
            throw ApiErrors.invalid("query.sort", "Unknown sort field.");
        }
        Map<String, Object> display = input.display() == null ? Map.of() : input.display();
        String displayJson = json.writeValueAsString(display);
        if (displayJson.length() > MAX_DISPLAY_JSON) {
            throw ApiErrors.invalid("display", "Display settings are too large.");
        }
        boolean shared = input.shared() == null ? existing != null && existing.shared() : input.shared();
        return new Validated(name, kind, projectId, json.writeValueAsString(query), displayJson, shared);
    }

    private record Row(UUID id, UUID projectId, String projectKey, UUID ownerId, String ownerName, String name,
            String kind, IssueFilter query, Map<String, Object> display, boolean shared, Instant updatedAt, long version) {

        boolean editableBy(OrgContext ctx) {
            return ownerId.equals(ctx.actorId()) || (shared && ctx.isAdmin());
        }

        SavedView toView(OrgContext ctx) {
            return new SavedView(id, name, kind, projectKey, query, display, shared, ownerId, ownerName, editableBy(ctx),
                    updatedAt, version);
        }
    }

    private Row row(ResultSet rs, int n) throws SQLException {
        IssueFilter query;
        Map<String, Object> display;
        try {
            query = json.readValue(rs.getString("query"), IssueFilter.class);
        } catch (JacksonException e) {
            query = IssueFilter.empty(); // a filter shape from an older release that no longer parses
        }
        try {
            display = json.readValue(rs.getString("display"), new TypeReference<Map<String, Object>>() {
            });
        } catch (JacksonException e) {
            display = Map.of();
        }
        return new Row(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class), rs.getString("project_key"),
                rs.getObject("owner_id", UUID.class), rs.getString("owner_name"), rs.getString("name"), rs.getString("kind"),
                query, display, rs.getBoolean("shared"), rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                rs.getLong("version"));
    }
}
