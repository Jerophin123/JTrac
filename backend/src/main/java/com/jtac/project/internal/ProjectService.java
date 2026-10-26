package com.jtac.project.internal;

import com.jtac.audit.AuditEntry;
import com.jtac.audit.AuditLog;
import com.jtac.organization.OrgContext;
import com.jtac.organization.OrgRole;
import com.jtac.organization.OrganizationAccess;
import com.jtac.project.ProjectAccess;
import com.jtac.project.ProjectCatalog;
import com.jtac.project.ProjectContext;
import com.jtac.project.ProjectCreated;
import com.jtac.project.ProjectDeleted;
import com.jtac.project.ProjectPermission;
import com.jtac.shared.Ids;
import com.jtac.shared.web.ApiErrors;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Project lifecycle and administration (roles, membership, issue types, labels, milestones). */
@Service
class ProjectService {

    private static final Pattern COLOR = Pattern.compile("^#[0-9a-f]{6}$");
    private static final Pattern ICON = Pattern.compile("^[a-z-]{1,30}$");

    private final JdbcClient jdbc;
    private final ProjectAccess access;
    private final ProjectCatalog catalog;
    private final OrganizationAccess organizations;
    private final ApplicationEventPublisher events;
    private final AuditLog audit;

    ProjectService(JdbcClient jdbc, ProjectAccess access, ProjectCatalog catalog, OrganizationAccess organizations,
            ApplicationEventPublisher events, AuditLog audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.catalog = catalog;
        this.organizations = organizations;
        this.events = events;
        this.audit = audit;
    }

    record ProjectDetail(UUID id, String key, String name, String description, String visibility, UUID leadId,
            String leadName, UUID defaultRoleId, boolean archived, OffsetDateTime createdAt, long version,
            List<ProjectPermission> myPermissions) {
    }

    record Member(UUID userId, String displayName, String handle, String email, UUID roleId, String roleName) {
    }

    record TeamGrant(UUID teamId, String teamName, UUID roleId, String roleName, int memberCount) {
    }

    // --- lifecycle -----------------------------------------------------------------------

    @Transactional
    ProjectDetail create(OrgContext org, String key, String name, String description, String visibility, String template) {
        if (org.role() == OrgRole.GUEST || (!org.isAdmin() && !org.settings().membersCanCreateProjects())) {
            throw ApiErrors.forbidden("You cannot create projects in this organization.");
        }
        String cleanKey = key == null ? "" : key.strip().toUpperCase(Locale.ROOT);
        if (!ProjectAccessService.KEY.matcher(cleanKey).matches()) {
            throw ApiErrors.invalid("key", "Use 2–10 capital letters or digits, starting with a letter (e.g. WEB).");
        }
        if (jdbc.sql("SELECT count(*) FROM project WHERE organization_id = :org AND key = :key")
                .param("org", org.organizationId()).param("key", cleanKey).query(Long.class).single() > 0) {
            throw ApiErrors.invalid("key", "Another project already uses this key.");
        }
        String vis = visibility == null ? org.settings().defaultProjectVisibility() : visibility;
        requireVisibility(vis);
        String tpl = template == null ? "KANBAN" : template;
        if (!Set.of("KANBAN", "STRUCTURED").contains(tpl)) {
            throw ApiErrors.invalid("template", "Use KANBAN or STRUCTURED.");
        }
        UUID projectId = Ids.newId();
        jdbc.sql("""
                INSERT INTO project (id, organization_id, key, name, description, visibility, lead_id)
                VALUES (:id, :org, :key, :name, :description, :visibility, :lead)
                """)
                .param("id", projectId).param("org", org.organizationId()).param("key", cleanKey)
                .param("name", requireName(name, 80)).param("description", cleanText(description, 5000, "description"))
                .param("visibility", vis).param("lead", org.actorId()).update();
        UUID adminRole = insertRole(org.organizationId(), projectId, "Administrator", ProjectPermission.ALL, "ADMIN", 0);
        UUID memberRole = insertRole(org.organizationId(), projectId, "Member", ProjectPermission.MEMBER, "MEMBER", 1);
        insertRole(org.organizationId(), projectId, "Viewer", ProjectPermission.VIEWER, "VIEWER", 2);
        jdbc.sql("UPDATE project SET default_role_id = :role WHERE id = :id").param("role", memberRole).param("id", projectId).update();
        jdbc.sql("INSERT INTO project_member (project_id, organization_id, user_id, role_id) VALUES (:p, :org, :user, :role)")
                .param("p", projectId).param("org", org.organizationId()).param("user", org.actorId()).param("role", adminRole).update();
        int position = 0;
        for (String[] type : List.of(new String[] {"Task", "check-square", "#5a7a5c"}, new String[] {"Bug", "bug", "#b02a1c"},
                new String[] {"Story", "bookmark", "#2f6f8a"}, new String[] {"Epic", "zap", "#7a4fa0"})) {
            insertType(org.organizationId(), projectId, type[0], type[1], type[2], false, position++);
        }
        insertType(org.organizationId(), projectId, "Sub-task", "corner-down-right", "#6c645a", true, position);
        events.publishEvent(new ProjectCreated(org.organizationId(), projectId, tpl, org.actorId()));
        audit.record(AuditEntry.of(org.organizationId(), org.actorId(), "project.created").target("project", projectId)
                .detail("key", cleanKey).detail("visibility", vis).detail("template", tpl));
        return detail(access.require(org, projectId));
    }

    @Transactional(readOnly = true)
    ProjectDetail detail(ProjectContext ctx) {
        return jdbc.sql("""
                SELECT p.id, p.key, p.name, p.description, p.visibility, p.lead_id, u.display_name AS lead_name,
                       p.default_role_id, p.archived_at IS NOT NULL AS archived, p.created_at, p.version
                  FROM project p LEFT JOIN user_account u ON u.id = p.lead_id WHERE p.id = :id
                """)
                .param("id", ctx.projectId())
                .query((rs, n) -> new ProjectDetail(rs.getObject("id", UUID.class), rs.getString("key"), rs.getString("name"),
                        rs.getString("description"), rs.getString("visibility"), rs.getObject("lead_id", UUID.class),
                        rs.getString("lead_name"), rs.getObject("default_role_id", UUID.class), rs.getBoolean("archived"),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getLong("version"),
                        ctx.permissions().stream().sorted().toList()))
                .single();
    }

    @Transactional
    ProjectDetail update(ProjectContext ctx, String name, String description, String visibility, UUID leadId,
            UUID defaultRoleId, long expectedVersion) {
        ctx.require(ProjectPermission.MANAGE_PROJECT);
        requireWritable(ctx);
        requireVisibility(visibility);
        if (leadId != null && organizations.roleOf(ctx.organizationId(), leadId).isEmpty()) {
            throw ApiErrors.invalid("leadId", "The lead must be a member of the organization.");
        }
        if (defaultRoleId != null && catalog.roles(ctx.projectId()).stream().noneMatch(r -> r.id().equals(defaultRoleId))) {
            throw ApiErrors.invalid("defaultRoleId", "Unknown role.");
        }
        int updated = jdbc.sql("""
                UPDATE project SET name = :name, description = :description, visibility = :visibility, lead_id = :lead,
                       default_role_id = :defaultRole, updated_at = now(), version = version + 1
                 WHERE id = :id AND version = :version
                """)
                .param("name", requireName(name, 80)).param("description", cleanText(description, 5000, "description"))
                .param("visibility", visibility).param("lead", leadId).param("defaultRole", defaultRoleId)
                .param("id", ctx.projectId()).param("version", expectedVersion).update();
        if (updated == 0) {
            throw new OptimisticLockingFailureException("project changed");
        }
        audit.record(AuditEntry.of(ctx.organizationId(), ctx.actorId(), "project.updated").target("project", ctx.projectId())
                .detail("visibility", visibility).detail("defaultRoleId", defaultRoleId));
        return detail(access.require(ctx.org(), ctx.projectId()));
    }

    @Transactional
    ProjectDetail setArchived(ProjectContext ctx, boolean archived) {
        ctx.require(ProjectPermission.MANAGE_PROJECT);
        jdbc.sql("UPDATE project SET archived_at = CASE WHEN :archived THEN now() ELSE NULL END, updated_at = now(), version = version + 1 WHERE id = :id")
                .param("archived", archived).param("id", ctx.projectId()).update();
        audit.record(AuditEntry.of(ctx.organizationId(), ctx.actorId(), archived ? "project.archived" : "project.restored")
                .target("project", ctx.projectId()));
        return detail(access.require(ctx.org(), ctx.projectId()));
    }

    /** Permanently deletes the project and all its issues. Organization admins only; requires the key typed. */
    @Transactional
    void delete(ProjectContext ctx, String confirmKey) {
        ctx.org().requireAdmin();
        if (!ctx.project().key().equals(confirmKey)) {
            throw ApiErrors.invalid("confirmKey", "Type the project key exactly to confirm.");
        }
        jdbc.sql("DELETE FROM project WHERE id = :id AND organization_id = :org")
                .param("id", ctx.projectId()).param("org", ctx.organizationId()).update();
        audit.record(AuditEntry.of(ctx.organizationId(), ctx.actorId(), "project.deleted").target("project", ctx.projectId())
                .detail("key", ctx.project().key()));
        events.publishEvent(new ProjectDeleted(ctx.organizationId(), ctx.projectId(), ctx.project().key()));
    }

    // --- roles ---------------------------------------------------------------------------

    @Transactional
    ProjectCatalog.Role createRole(ProjectContext ctx, String name, Collection<ProjectPermission> permissions) {
        manage(ctx);
        Set<ProjectPermission> perms = permissionsWithBrowse(permissions);
        int position = catalog.roles(ctx.projectId()).size();
        UUID id = insertRole(ctx.organizationId(), ctx.projectId(), requireName(name, 40), perms, null, position);
        audit.record(AuditEntry.of(ctx.organizationId(), ctx.actorId(), "project.role_created").target("project_role", id)
                .detail("permissions", perms.toString()));
        return role(ctx, id);
    }

    @Transactional
    ProjectCatalog.Role updateRole(ProjectContext ctx, UUID roleId, String name, Collection<ProjectPermission> permissions) {
        manage(ctx);
        ProjectCatalog.Role existing = role(ctx, roleId);
        Set<ProjectPermission> perms = "ADMIN".equals(existing.systemKey()) ? ProjectPermission.ALL : permissionsWithBrowse(permissions);
        jdbc.sql("UPDATE project_role SET name = :name, permissions = :perms WHERE id = :id AND project_id = :p")
                .param("name", requireName(name, 40)).param("perms", perms.stream().map(Enum::name).sorted().toArray(String[]::new))
                .param("id", roleId).param("p", ctx.projectId()).update();
        audit.record(AuditEntry.of(ctx.organizationId(), ctx.actorId(), "project.role_updated").target("project_role", roleId)
                .detail("permissions", perms.toString()));
        return role(ctx, roleId);
    }

    @Transactional
    void deleteRole(ProjectContext ctx, UUID roleId) {
        manage(ctx);
        ProjectCatalog.Role existing = role(ctx, roleId);
        if (existing.systemKey() != null) {
            throw ApiErrors.conflict("Built-in roles cannot be deleted.");
        }
        long uses = jdbc.sql("""
                SELECT (SELECT count(*) FROM project_member WHERE role_id = :id) + (SELECT count(*) FROM project_team WHERE role_id = :id)
                """).param("id", roleId).query(Long.class).single();
        if (uses > 0) {
            throw ApiErrors.conflict("The role is still assigned to " + uses + " member(s) or team(s).");
        }
        jdbc.sql("DELETE FROM project_role WHERE id = :id AND project_id = :p").param("id", roleId).param("p", ctx.projectId()).update();
        audit.record(AuditEntry.of(ctx.organizationId(), ctx.actorId(), "project.role_deleted").target("project_role", roleId));
    }

    // --- members and teams ---------------------------------------------------------------

    @Transactional(readOnly = true)
    List<Member> members(ProjectContext ctx) {
        return jdbc.sql("""
                SELECT u.id, u.display_name, u.handle, u.email, r.id AS role_id, r.name AS role_name
                  FROM project_member pm JOIN user_account u ON u.id = pm.user_id JOIN project_role r ON r.id = pm.role_id
                 WHERE pm.project_id = :p ORDER BY lower(u.display_name)
                """)
                .param("p", ctx.projectId())
                .query((rs, n) -> new Member(rs.getObject("id", UUID.class), rs.getString("display_name"), rs.getString("handle"),
                        rs.getString("email"), rs.getObject("role_id", UUID.class), rs.getString("role_name")))
                .list();
    }

    @Transactional(readOnly = true)
    List<TeamGrant> teams(ProjectContext ctx) {
        return jdbc.sql("""
                SELECT t.id, t.name, r.id AS role_id, r.name AS role_name,
                       (SELECT count(*) FROM team_member tm WHERE tm.team_id = t.id) AS members
                  FROM project_team pt JOIN team t ON t.id = pt.team_id JOIN project_role r ON r.id = pt.role_id
                 WHERE pt.project_id = :p ORDER BY lower(t.name)
                """)
                .param("p", ctx.projectId())
                .query((rs, n) -> new TeamGrant(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getObject("role_id", UUID.class), rs.getString("role_name"), rs.getInt("members")))
                .list();
    }

    @Transactional
    void putMember(ProjectContext ctx, UUID userId, UUID roleId) {
        manage(ctx);
        role(ctx, roleId);
        if (organizations.roleOf(ctx.organizationId(), userId).isEmpty()) {
            throw ApiErrors.invalid("userId", "Only organization members can be added to a project.");
        }
        jdbc.sql("""
                INSERT INTO project_member (project_id, organization_id, user_id, role_id) VALUES (:p, :org, :user, :role)
                ON CONFLICT (project_id, user_id) DO UPDATE SET role_id = EXCLUDED.role_id
                """)
                .param("p", ctx.projectId()).param("org", ctx.organizationId()).param("user", userId).param("role", roleId).update();
        audit.record(AuditEntry.of(ctx.organizationId(), ctx.actorId(), "project.member_set").target("project", ctx.projectId())
                .detail("user", userId).detail("role", roleId));
    }

    @Transactional
    void removeMember(ProjectContext ctx, UUID userId) {
        manage(ctx);
        jdbc.sql("DELETE FROM project_member WHERE project_id = :p AND user_id = :user")
                .param("p", ctx.projectId()).param("user", userId).update();
        audit.record(AuditEntry.of(ctx.organizationId(), ctx.actorId(), "project.member_removed").target("project", ctx.projectId())
                .detail("user", userId));
    }

    @Transactional
    void putTeam(ProjectContext ctx, UUID teamId, UUID roleId) {
        manage(ctx);
        role(ctx, roleId);
        if (!organizations.teamExists(ctx.organizationId(), teamId)) {
            throw ApiErrors.invalid("teamId", "Unknown team.");
        }
        jdbc.sql("""
                INSERT INTO project_team (project_id, organization_id, team_id, role_id) VALUES (:p, :org, :team, :role)
                ON CONFLICT (project_id, team_id) DO UPDATE SET role_id = EXCLUDED.role_id
                """)
                .param("p", ctx.projectId()).param("org", ctx.organizationId()).param("team", teamId).param("role", roleId).update();
        audit.record(AuditEntry.of(ctx.organizationId(), ctx.actorId(), "project.team_set").target("project", ctx.projectId())
                .detail("team", teamId).detail("role", roleId));
    }

    @Transactional
    void removeTeam(ProjectContext ctx, UUID teamId) {
        manage(ctx);
        jdbc.sql("DELETE FROM project_team WHERE project_id = :p AND team_id = :team").param("p", ctx.projectId()).param("team", teamId).update();
        audit.record(AuditEntry.of(ctx.organizationId(), ctx.actorId(), "project.team_removed").target("project", ctx.projectId())
                .detail("team", teamId));
    }

    // --- issue types, labels, milestones -------------------------------------------------

    @Transactional
    ProjectCatalog.IssueType createType(ProjectContext ctx, String name, String icon, String color, boolean subtask) {
        manage(ctx);
        UUID id = insertType(ctx.organizationId(), ctx.projectId(), requireName(name, 40), requireIcon(icon), requireColor(color),
                subtask, catalog.issueTypes(ctx.projectId()).size());
        return catalog.issueTypes(ctx.projectId()).stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    ProjectCatalog.IssueType updateType(ProjectContext ctx, UUID typeId, String name, String icon, String color, boolean archived) {
        manage(ctx);
        if (archived) {
            long active = catalog.issueTypes(ctx.projectId()).stream().filter(t -> !t.archived() && !t.id().equals(typeId) && !t.subtask()).count();
            if (active == 0) {
                throw ApiErrors.conflict("A project needs at least one active (non sub-task) issue type.");
            }
        }
        int updated = jdbc.sql("""
                UPDATE issue_type SET name = :name, icon = :icon, color = :color,
                       archived_at = CASE WHEN :archived THEN coalesce(archived_at, now()) ELSE NULL END
                 WHERE id = :id AND project_id = :p
                """)
                .param("name", requireName(name, 40)).param("icon", requireIcon(icon)).param("color", requireColor(color))
                .param("archived", archived).param("id", typeId).param("p", ctx.projectId()).update();
        if (updated == 0) {
            throw ApiErrors.notFound("Issue type");
        }
        return catalog.issueTypes(ctx.projectId()).stream().filter(t -> t.id().equals(typeId)).findFirst().orElseThrow();
    }

    @Transactional
    ProjectCatalog.Label createLabel(ProjectContext ctx, String name, String color) {
        ctx.require(ProjectPermission.EDIT_ISSUES);
        requireWritable(ctx);
        UUID id = Ids.newId();
        jdbc.sql("INSERT INTO label (id, organization_id, project_id, name, color) VALUES (:id, :org, :p, :name, :color)")
                .param("id", id).param("org", ctx.organizationId()).param("p", ctx.projectId())
                .param("name", requireName(name, 40)).param("color", requireColor(color)).update();
        return new ProjectCatalog.Label(id, name.strip(), color);
    }

    @Transactional
    ProjectCatalog.Label updateLabel(ProjectContext ctx, UUID labelId, String name, String color) {
        manage(ctx);
        if (jdbc.sql("UPDATE label SET name = :name, color = :color WHERE id = :id AND project_id = :p")
                .param("name", requireName(name, 40)).param("color", requireColor(color))
                .param("id", labelId).param("p", ctx.projectId()).update() == 0) {
            throw ApiErrors.notFound("Label");
        }
        return new ProjectCatalog.Label(labelId, name.strip(), color);
    }

    @Transactional
    void deleteLabel(ProjectContext ctx, UUID labelId) {
        manage(ctx);
        if (jdbc.sql("DELETE FROM label WHERE id = :id AND project_id = :p").param("id", labelId).param("p", ctx.projectId()).update() == 0) {
            throw ApiErrors.notFound("Label");
        }
    }

    @Transactional
    ProjectCatalog.Milestone saveMilestone(ProjectContext ctx, UUID milestoneId, String name, String description,
            LocalDate start, LocalDate due, String state, Long expectedVersion) {
        manage(ctx);
        if (start != null && due != null && start.isAfter(due)) {
            throw ApiErrors.invalid("dueDate", "The due date must not be before the start date.");
        }
        String cleanState = state == null ? "OPEN" : state;
        if (!Set.of("OPEN", "CLOSED").contains(cleanState)) {
            throw ApiErrors.invalid("state", "Use OPEN or CLOSED.");
        }
        UUID id = milestoneId == null ? Ids.newId() : milestoneId;
        if (milestoneId == null) {
            jdbc.sql("""
                    INSERT INTO milestone (id, organization_id, project_id, name, description, start_date, due_date, state)
                    VALUES (:id, :org, :p, :name, :description, :start, :due, :state)
                    """)
                    .param("id", id).param("org", ctx.organizationId()).param("p", ctx.projectId())
                    .param("name", requireName(name, 80)).param("description", cleanText(description, 5000, "description"))
                    .param("start", start).param("due", due).param("state", cleanState).update();
        } else {
            int updated = jdbc.sql("""
                    UPDATE milestone SET name = :name, description = :description, start_date = :start, due_date = :due,
                           state = :state, updated_at = now(), version = version + 1
                     WHERE id = :id AND project_id = :p AND version = :version
                    """)
                    .param("name", requireName(name, 80)).param("description", cleanText(description, 5000, "description"))
                    .param("start", start).param("due", due).param("state", cleanState)
                    .param("id", id).param("p", ctx.projectId()).param("version", expectedVersion == null ? -1 : expectedVersion).update();
            if (updated == 0) {
                if (catalog.milestones(ctx.projectId()).stream().noneMatch(m -> m.id().equals(id))) {
                    throw ApiErrors.notFound("Milestone");
                }
                throw new OptimisticLockingFailureException("milestone changed");
            }
        }
        return catalog.milestones(ctx.projectId()).stream().filter(m -> m.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    void deleteMilestone(ProjectContext ctx, UUID milestoneId) {
        manage(ctx);
        if (jdbc.sql("DELETE FROM milestone WHERE id = :id AND project_id = :p").param("id", milestoneId)
                .param("p", ctx.projectId()).update() == 0) {
            throw ApiErrors.notFound("Milestone");
        }
    }

    // --- helpers -------------------------------------------------------------------------

    private static void manage(ProjectContext ctx) {
        ctx.require(ProjectPermission.MANAGE_PROJECT);
        requireWritable(ctx);
    }

    private static void requireWritable(ProjectContext ctx) {
        if (ctx.project().archived()) {
            throw ApiErrors.forbidden("This project is archived and read-only. Restore it first.");
        }
    }

    private ProjectCatalog.Role role(ProjectContext ctx, UUID roleId) {
        return catalog.roles(ctx.projectId()).stream().filter(r -> r.id().equals(roleId)).findFirst()
                .orElseThrow(() -> ApiErrors.notFound("Role"));
    }

    private UUID insertRole(UUID orgId, UUID projectId, String name, Set<ProjectPermission> perms, String systemKey, int position) {
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO project_role (id, organization_id, project_id, name, permissions, system_key, position)
                VALUES (:id, :org, :p, :name, :perms, :system, :position)
                """)
                .param("id", id).param("org", orgId).param("p", projectId).param("name", name)
                .param("perms", perms.stream().map(Enum::name).sorted().toArray(String[]::new))
                .param("system", systemKey).param("position", position).update();
        return id;
    }

    private UUID insertType(UUID orgId, UUID projectId, String name, String icon, String color, boolean subtask, int position) {
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO issue_type (id, organization_id, project_id, name, icon, color, is_subtask, position)
                VALUES (:id, :org, :p, :name, :icon, :color, :subtask, :position)
                """)
                .param("id", id).param("org", orgId).param("p", projectId).param("name", name).param("icon", icon)
                .param("color", color).param("subtask", subtask).param("position", position).update();
        return id;
    }

    private static Set<ProjectPermission> permissionsWithBrowse(Collection<ProjectPermission> permissions) {
        Set<ProjectPermission> perms = permissions == null || permissions.isEmpty()
                ? EnumSet.noneOf(ProjectPermission.class) : EnumSet.copyOf(permissions);
        perms.add(ProjectPermission.BROWSE);
        return perms;
    }

    private static void requireVisibility(String visibility) {
        if (!Set.of("PRIVATE", "ORGANIZATION").contains(visibility)) {
            throw ApiErrors.invalid("visibility", "Use PRIVATE or ORGANIZATION.");
        }
    }

    static String requireName(String name, int max) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty() || clean.length() > max) {
            throw ApiErrors.invalid("name", "Use 1 to " + max + " characters.");
        }
        return clean;
    }

    static String cleanText(String text, int max, String field) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if (text.length() > max) {
            throw ApiErrors.invalid(field, "At most " + max + " characters.");
        }
        return text.strip();
    }

    private static String requireColor(String color) {
        String clean = color == null ? "#6c645a" : color.strip().toLowerCase(Locale.ROOT);
        if (!COLOR.matcher(clean).matches()) {
            throw ApiErrors.invalid("color", "Use a hex color such as #b4532a.");
        }
        return clean;
    }

    private static String requireIcon(String icon) {
        String clean = icon == null ? "square" : icon.strip();
        if (!ICON.matcher(clean).matches()) {
            throw ApiErrors.invalid("icon", "Use an icon name such as 'bug'.");
        }
        return clean;
    }
}
