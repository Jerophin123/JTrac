package com.jtac.organization.internal;

import com.jtac.audit.AuditEntry;
import com.jtac.audit.AuditLog;
import com.jtac.organization.OrgContext;
import com.jtac.shared.Ids;
import com.jtac.shared.web.ApiErrors;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Teams group organization members; a team can be given a role in projects. Admins manage teams. */
@Service
class TeamService {

    private final JdbcClient jdbc;
    private final AuditLog audit;

    TeamService(JdbcClient jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    record TeamMember(UUID userId, String displayName, String handle) {
    }

    record Team(UUID id, String name, String description, List<TeamMember> members) {
    }

    @Transactional(readOnly = true)
    List<Team> list(OrgContext ctx) {
        List<Team> teams = jdbc.sql("SELECT id, name, description FROM team WHERE organization_id = :org ORDER BY lower(name)")
                .param("org", ctx.organizationId())
                .query((rs, n) -> new Team(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"), List.of()))
                .list();
        return teams.stream().map(t -> new Team(t.id(), t.name(), t.description(), members(ctx, t.id()))).toList();
    }

    @Transactional
    Team create(OrgContext ctx, String name, String description) {
        ctx.requireAdmin();
        UUID id = Ids.newId();
        jdbc.sql("INSERT INTO team (id, organization_id, name, description) VALUES (:id, :org, :name, :description)")
                .param("id", id).param("org", ctx.organizationId()).param("name", requireName(name))
                .param("description", clean(description)).update();
        audit.record(AuditEntry.of(ctx.organizationId(), ctx.actorId(), "team.created").target("team", id).detail("name", name));
        return new Team(id, name.strip(), clean(description), List.of());
    }

    @Transactional
    Team update(OrgContext ctx, UUID teamId, String name, String description) {
        ctx.requireAdmin();
        int updated = jdbc.sql("UPDATE team SET name = :name, description = :description WHERE id = :id AND organization_id = :org")
                .param("name", requireName(name)).param("description", clean(description))
                .param("id", teamId).param("org", ctx.organizationId()).update();
        if (updated == 0) {
            throw ApiErrors.notFound("Team");
        }
        audit.record(AuditEntry.of(ctx.organizationId(), ctx.actorId(), "team.updated").target("team", teamId));
        return new Team(teamId, name.strip(), clean(description), members(ctx, teamId));
    }

    @Transactional
    void delete(OrgContext ctx, UUID teamId) {
        ctx.requireAdmin();
        if (jdbc.sql("DELETE FROM team WHERE id = :id AND organization_id = :org").param("id", teamId)
                .param("org", ctx.organizationId()).update() == 0) {
            throw ApiErrors.notFound("Team");
        }
        audit.record(AuditEntry.of(ctx.organizationId(), ctx.actorId(), "team.deleted").target("team", teamId));
    }

    @Transactional
    void addMember(OrgContext ctx, UUID teamId, UUID userId) {
        ctx.requireAdmin();
        requireTeam(ctx, teamId);
        boolean member = jdbc.sql("SELECT count(*) FROM membership WHERE organization_id = :org AND user_id = :user")
                .param("org", ctx.organizationId()).param("user", userId).query(Long.class).single() > 0;
        if (!member) {
            throw ApiErrors.notFound("Member");
        }
        jdbc.sql("""
                INSERT INTO team_member (team_id, organization_id, user_id) VALUES (:team, :org, :user)
                ON CONFLICT DO NOTHING
                """)
                .param("team", teamId).param("org", ctx.organizationId()).param("user", userId).update();
        audit.record(AuditEntry.of(ctx.organizationId(), ctx.actorId(), "team.member_added").target("team", teamId).detail("user", userId));
    }

    @Transactional
    void removeMember(OrgContext ctx, UUID teamId, UUID userId) {
        ctx.requireAdmin();
        requireTeam(ctx, teamId);
        jdbc.sql("DELETE FROM team_member WHERE team_id = :team AND organization_id = :org AND user_id = :user")
                .param("team", teamId).param("org", ctx.organizationId()).param("user", userId).update();
        audit.record(AuditEntry.of(ctx.organizationId(), ctx.actorId(), "team.member_removed").target("team", teamId).detail("user", userId));
    }

    private List<TeamMember> members(OrgContext ctx, UUID teamId) {
        return jdbc.sql("""
                SELECT u.id, u.display_name, u.handle FROM team_member tm JOIN user_account u ON u.id = tm.user_id
                 WHERE tm.team_id = :team AND tm.organization_id = :org ORDER BY lower(u.display_name)
                """)
                .param("team", teamId).param("org", ctx.organizationId())
                .query((rs, n) -> new TeamMember(rs.getObject("id", UUID.class), rs.getString("display_name"), rs.getString("handle")))
                .list();
    }

    private void requireTeam(OrgContext ctx, UUID teamId) {
        if (jdbc.sql("SELECT count(*) FROM team WHERE id = :id AND organization_id = :org").param("id", teamId)
                .param("org", ctx.organizationId()).query(Long.class).single() == 0) {
            throw ApiErrors.notFound("Team");
        }
    }

    private static String requireName(String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty() || clean.length() > 60) {
            throw ApiErrors.invalid("name", "Use 1 to 60 characters.");
        }
        return clean;
    }

    private static String clean(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        if (description.length() > 500) {
            throw ApiErrors.invalid("description", "At most 500 characters.");
        }
        return description.strip();
    }
}
