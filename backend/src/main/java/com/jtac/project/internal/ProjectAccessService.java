package com.jtac.project.internal;

import com.jtac.organization.OrgContext;
import com.jtac.organization.OrgRole;
import com.jtac.organization.OrganizationAccess;
import com.jtac.project.ProjectAccess;
import com.jtac.project.ProjectCatalog;
import com.jtac.project.ProjectContext;
import com.jtac.project.ProjectPermission;
import com.jtac.project.ProjectRef;
import com.jtac.shared.web.ApiErrors;
import java.sql.Array;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes effective project roles: direct membership, team grants, the default role of
 * organization-visible projects (not for guests), and the system ADMIN role for
 * organization owners/admins. Permissions are the union of the roles' permissions;
 * archived projects keep only BROWSE and MANAGE_PROJECT.
 */
@Service
class ProjectAccessService implements ProjectAccess, ProjectCatalog {

    static final Pattern KEY = Pattern.compile("^[A-Z][A-Z0-9]{1,9}$");

    private static final String GRANTS_SQL = """
            SELECT g.*, ARRAY(SELECT DISTINCT unnest(r.permissions) FROM project_role r WHERE r.id = ANY (g.role_ids)) AS perms
              FROM (
            SELECT p.id, p.organization_id, p.key, p.name, p.visibility, p.archived_at IS NOT NULL AS archived,
                   p.archived_at, lower(p.name) AS sort_name,
                   ARRAY(
                       SELECT pm.role_id FROM project_member pm WHERE pm.project_id = p.id AND pm.user_id = :user
                       UNION
                       SELECT pt.role_id FROM project_team pt
                         JOIN team_member tm ON tm.team_id = pt.team_id AND tm.user_id = :user
                        WHERE pt.project_id = p.id
                       UNION
                       SELECT p.default_role_id WHERE p.visibility = 'ORGANIZATION' AND :orgRole <> 'GUEST'
                                                  AND p.default_role_id IS NOT NULL
                       UNION
                       SELECT r.id FROM project_role r WHERE r.project_id = p.id AND r.system_key = 'ADMIN' AND :orgAdmin
                   ) AS role_ids
              FROM project p
             WHERE p.organization_id = :org %s
              ) g
             ORDER BY g.archived_at NULLS FIRST, g.sort_name
            """;

    private final JdbcClient jdbc;
    private final OrganizationAccess organizations;

    ProjectAccessService(JdbcClient jdbc, OrganizationAccess organizations) {
        this.jdbc = jdbc;
        this.organizations = organizations;
    }

    private record Row(ProjectRef ref, Set<UUID> roleIds, Set<ProjectPermission> permissions) {
    }

    // --- ProjectAccess -------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public ProjectContext require(OrgContext org, String projectKey) {
        if (projectKey == null || !KEY.matcher(projectKey).matches()) {
            throw ApiErrors.notFound("Project");
        }
        return rows(org.organizationId(), org.actorId(), org.role(), " AND p.key = :key", Map.of("key", projectKey))
                .stream().findFirst().flatMap(row -> toContext(org, row))
                .orElseThrow(() -> ApiErrors.notFound("Project"));
    }

    @Override
    @Transactional(readOnly = true)
    public ProjectContext require(OrgContext org, UUID projectId) {
        return rows(org.organizationId(), org.actorId(), org.role(), " AND p.id = :id", Map.of("id", projectId))
                .stream().findFirst().flatMap(row -> toContext(org, row))
                .orElseThrow(() -> ApiErrors.notFound("Project"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProjectContext> visible(OrgContext org) {
        return rows(org.organizationId(), org.actorId(), org.role(), "", Map.of())
                .stream().map(row -> toContext(org, row)).flatMap(Optional::stream).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, ProjectContext> visible(OrgContext org, Collection<UUID> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ProjectContext> result = new LinkedHashMap<>();
        rows(org.organizationId(), org.actorId(), org.role(), " AND p.id IN (:ids)", Map.of("ids", new HashSet<>(projectIds)))
                .forEach(row -> toContext(org, row).ifPresent(ctx -> result.put(ctx.projectId(), ctx)));
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Grants> grantsFor(UUID organizationId, UUID projectId, UUID userId) {
        Optional<OrgRole> role = organizations.roleOf(organizationId, userId);
        if (role.isEmpty()) {
            return Optional.empty();
        }
        return rows(organizationId, userId, role.get(), " AND p.id = :id", Map.of("id", projectId)).stream().findFirst()
                .map(row -> new Grants(row.roleIds(), row.permissions()))
                .filter(grants -> grants.has(ProjectPermission.BROWSE));
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> browsers(UUID organizationId, UUID projectId) {
        return Set.copyOf(jdbc.sql("""
                SELECT m.user_id FROM membership m JOIN project p ON p.organization_id = m.organization_id AND p.id = :project
                 WHERE m.organization_id = :org AND (
                       m.role IN ('OWNER', 'ADMIN')
                    OR (p.visibility = 'ORGANIZATION' AND m.role <> 'GUEST'
                        AND EXISTS (SELECT 1 FROM project_role r WHERE r.id = p.default_role_id AND 'BROWSE' = ANY (r.permissions)))
                    OR EXISTS (SELECT 1 FROM project_member pm JOIN project_role r ON r.id = pm.role_id
                                WHERE pm.project_id = p.id AND pm.user_id = m.user_id AND 'BROWSE' = ANY (r.permissions))
                    OR EXISTS (SELECT 1 FROM project_team pt JOIN team_member tm ON tm.team_id = pt.team_id
                                 JOIN project_role r ON r.id = pt.role_id
                                WHERE pt.project_id = p.id AND tm.user_id = m.user_id AND 'BROWSE' = ANY (r.permissions)))
                """)
                .param("org", organizationId).param("project", projectId).query(UUID.class).list());
    }

    // --- ProjectCatalog ------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<IssueType> issueTypes(UUID projectId) {
        return jdbc.sql("""
                SELECT id, name, icon, color, is_subtask, position, archived_at IS NOT NULL AS archived
                  FROM issue_type WHERE project_id = :p ORDER BY position, lower(name)
                """)
                .param("p", projectId)
                .query((rs, n) -> new IssueType(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("icon"),
                        rs.getString("color"), rs.getBoolean("is_subtask"), rs.getInt("position"), rs.getBoolean("archived")))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Label> labels(UUID projectId) {
        return jdbc.sql("SELECT id, name, color FROM label WHERE project_id = :p ORDER BY lower(name)")
                .param("p", projectId)
                .query((rs, n) -> new Label(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("color")))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Milestone> milestones(UUID projectId) {
        return jdbc.sql("""
                SELECT id, name, description, start_date, due_date, state, version FROM milestone
                 WHERE project_id = :p ORDER BY state DESC, due_date NULLS LAST, lower(name)
                """)
                .param("p", projectId)
                .query((rs, n) -> new Milestone(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("description"), rs.getObject("start_date", LocalDate.class),
                        rs.getObject("due_date", LocalDate.class), rs.getString("state"), rs.getLong("version")))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Role> roles(UUID projectId) {
        return jdbc.sql("SELECT id, name, permissions, system_key, position FROM project_role WHERE project_id = :p ORDER BY position, lower(name)")
                .param("p", projectId)
                .query((rs, n) -> new Role(rs.getObject("id", UUID.class), rs.getString("name"),
                        Arrays.stream(strings(rs.getArray("permissions"))).map(ProjectPermission::valueOf).sorted().toList(),
                        rs.getString("system_key"), rs.getInt("position")))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> adminRoleId(UUID projectId) {
        return jdbc.sql("SELECT id FROM project_role WHERE project_id = :p AND system_key = 'ADMIN'")
                .param("p", projectId).query(UUID.class).optional();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> description(UUID projectId) {
        return jdbc.sql("SELECT description FROM project WHERE id = :p").param("p", projectId)
                .query((rs, n) -> Optional.ofNullable(rs.getString("description"))).optional().flatMap(d -> d);
    }

    // --- helpers -------------------------------------------------------------------------

    private List<Row> rows(UUID organizationId, UUID userId, OrgRole orgRole, String condition, Map<String, Object> extra) {
        var spec = jdbc.sql(GRANTS_SQL.formatted(condition))
                .param("org", organizationId).param("user", userId)
                .param("orgRole", orgRole.name()).param("orgAdmin", orgRole.isAdmin());
        for (var entry : extra.entrySet()) {
            spec = spec.param(entry.getKey(), entry.getValue());
        }
        return spec.query((rs, n) -> {
            boolean archived = rs.getBoolean("archived");
            Set<ProjectPermission> permissions = EnumSet.noneOf(ProjectPermission.class);
            Arrays.stream(strings(rs.getArray("perms"))).map(ProjectPermission::valueOf).forEach(permissions::add);
            if (archived) {
                permissions.retainAll(EnumSet.of(ProjectPermission.BROWSE, ProjectPermission.MANAGE_PROJECT));
            }
            return new Row(new ProjectRef(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                    rs.getString("key"), rs.getString("name"), rs.getString("visibility"), archived),
                    uuids(rs.getArray("role_ids")), permissions);
        }).list();
    }

    private static Optional<ProjectContext> toContext(OrgContext org, Row row) {
        if (!row.permissions().contains(ProjectPermission.BROWSE)) {
            return Optional.empty();
        }
        return Optional.of(new ProjectContext(row.ref(), org, row.roleIds(), row.permissions()));
    }

    private static Set<UUID> uuids(Array array) throws SQLException {
        Set<UUID> result = new HashSet<>();
        for (Object value : (Object[]) array.getArray()) {
            if (value != null) {
                result.add((UUID) value);
            }
        }
        return result;
    }

    private static String[] strings(Array array) throws SQLException {
        return (String[]) array.getArray();
    }
}
