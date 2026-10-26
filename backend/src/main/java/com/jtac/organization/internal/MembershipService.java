package com.jtac.organization.internal;

import com.jtac.audit.AuditEntry;
import com.jtac.audit.AuditLog;
import com.jtac.organization.OrgContext;
import com.jtac.organization.OrgRole;
import com.jtac.shared.web.ApiErrors;
import com.jtac.shared.web.PageResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Membership rules: only owners may grant, change or remove the OWNER role; an organization
 * always keeps at least one owner; admins manage non-owner members; anyone may leave
 * (except the last owner). Removing a member cascades their project memberships, team
 * memberships, watches and notifications, and unassigns their issues.
 */
@Service
class MembershipService {

    private final JdbcClient jdbc;
    private final AuditLog audit;

    MembershipService(JdbcClient jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    record Member(UUID userId, String email, String displayName, String handle, OrgRole role, Instant joinedAt,
            List<String> teams) {
    }

    @Transactional(readOnly = true)
    PageResponse<Member> list(OrgContext ctx, String query, int page, int size) {
        String q = query == null ? "" : query.strip().toLowerCase(java.util.Locale.ROOT);
        String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        long total = jdbc.sql("""
                SELECT count(*) FROM membership m JOIN user_account u ON u.id = m.user_id
                 WHERE m.organization_id = :org AND (:q = '' OR lower(u.display_name) LIKE :like OR u.email_normalized LIKE :like OR u.handle LIKE :like)
                """)
                .param("org", ctx.organizationId()).param("q", q).param("like", like).query(Long.class).single();
        List<Member> items = jdbc.sql("""
                SELECT u.id, u.email, u.display_name, u.handle, m.role, m.joined_at,
                       coalesce((SELECT array_agg(t.name ORDER BY lower(t.name)) FROM team_member tm JOIN team t ON t.id = tm.team_id
                                  WHERE tm.organization_id = m.organization_id AND tm.user_id = m.user_id), '{}') AS teams
                  FROM membership m JOIN user_account u ON u.id = m.user_id
                 WHERE m.organization_id = :org AND (:q = '' OR lower(u.display_name) LIKE :like OR u.email_normalized LIKE :like OR u.handle LIKE :like)
                 ORDER BY lower(u.display_name), u.id
                 LIMIT :limit OFFSET :offset
                """)
                .param("org", ctx.organizationId()).param("q", q).param("like", like)
                .param("limit", size).param("offset", (long) page * size)
                .query((rs, n) -> new Member(rs.getObject("id", UUID.class), rs.getString("email"),
                        rs.getString("display_name"), rs.getString("handle"), OrgRole.valueOf(rs.getString("role")),
                        rs.getObject("joined_at", OffsetDateTime.class).toInstant(),
                        List.of((String[]) rs.getArray("teams").getArray())))
                .list();
        return new PageResponse<>(items, page, size, total);
    }

    @Transactional
    void changeRole(OrgContext ctx, UUID userId, OrgRole newRole) {
        ctx.requireAdmin();
        OrgRole current = roleOrThrow(ctx, userId);
        if (current == newRole) {
            return;
        }
        if ((current == OrgRole.OWNER || newRole == OrgRole.OWNER) && ctx.role() != OrgRole.OWNER) {
            throw ApiErrors.forbidden("Only owners can grant or revoke the owner role.");
        }
        if (current == OrgRole.OWNER) {
            lockAndRequireAnotherOwner(ctx, userId);
        }
        jdbc.sql("UPDATE membership SET role = :role WHERE organization_id = :org AND user_id = :user")
                .param("role", newRole.name()).param("org", ctx.organizationId()).param("user", userId).update();
        audit.record(AuditEntry.of(ctx.organizationId(), ctx.actorId(), "membership.role_changed")
                .target("user", userId).detail("from", current.name()).detail("to", newRole.name()));
    }

    @Transactional
    void remove(OrgContext ctx, UUID userId) {
        boolean self = userId.equals(ctx.actorId());
        OrgRole current = roleOrThrow(ctx, userId);
        if (!self) {
            ctx.requireAdmin();
            if (current == OrgRole.OWNER && ctx.role() != OrgRole.OWNER) {
                throw ApiErrors.forbidden("Only owners can remove an owner.");
            }
        }
        if (current == OrgRole.OWNER) {
            lockAndRequireAnotherOwner(ctx, userId);
        }
        jdbc.sql("DELETE FROM membership WHERE organization_id = :org AND user_id = :user")
                .param("org", ctx.organizationId()).param("user", userId).update();
        audit.record(AuditEntry.of(ctx.organizationId(), ctx.actorId(), self ? "membership.left" : "membership.removed")
                .target("user", userId).detail("role", current.name()));
    }

    @Transactional
    void addMember(UUID organizationId, UUID userId, OrgRole role, UUID actorId) {
        jdbc.sql("""
                INSERT INTO membership (organization_id, user_id, role) VALUES (:org, :user, :role)
                ON CONFLICT (organization_id, user_id) DO NOTHING
                """)
                .param("org", organizationId).param("user", userId).param("role", role.name()).update();
        audit.record(AuditEntry.of(organizationId, actorId, "membership.added").target("user", userId).detail("role", role.name()));
    }

    private OrgRole roleOrThrow(OrgContext ctx, UUID userId) {
        return jdbc.sql("SELECT role FROM membership WHERE organization_id = :org AND user_id = :user")
                .param("org", ctx.organizationId()).param("user", userId)
                .query(String.class).optional().map(OrgRole::valueOf)
                .orElseThrow(() -> ApiErrors.notFound("Member"));
    }

    /** Serializes owner changes per organization (row lock) and ensures another owner remains. */
    private void lockAndRequireAnotherOwner(OrgContext ctx, UUID leavingOwner) {
        jdbc.sql("SELECT id FROM organization WHERE id = :org FOR UPDATE").param("org", ctx.organizationId()).query(UUID.class).single();
        long otherOwners = jdbc.sql("SELECT count(*) FROM membership WHERE organization_id = :org AND role = 'OWNER' AND user_id <> :user")
                .param("org", ctx.organizationId()).param("user", leavingOwner).query(Long.class).single();
        if (otherOwners == 0) {
            throw ApiErrors.conflict("An organization must keep at least one owner. Make someone else an owner first.");
        }
    }
}
