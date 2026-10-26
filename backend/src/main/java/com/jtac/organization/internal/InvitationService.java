package com.jtac.organization.internal;

import com.jtac.audit.AuditEntry;
import com.jtac.audit.AuditLog;
import com.jtac.identity.Consent;
import com.jtac.identity.CurrentUser;
import com.jtac.identity.UserDirectory;
import com.jtac.mail.EmailTemplate;
import com.jtac.mail.Mailer;
import com.jtac.organization.OrgContext;
import com.jtac.organization.OrgRole;
import com.jtac.shared.Ids;
import com.jtac.shared.Tokens;
import com.jtac.shared.web.ApiErrors;
import com.jtac.shared.web.ApiProblemException;
import com.jtac.shared.web.ProblemTypes;
import com.jtac.shared.web.RateLimiter;
import java.io.Serial;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriUtils;

/**
 * Invitations by email. The token is sent only to the invited address; accepting proves
 * ownership of that mailbox. A signed-in user can accept only invitations addressed to
 * their own email; someone without an account creates one while accepting.
 */
@Service
class InvitationService {

    static final Duration TTL = Duration.ofDays(7);

    private final JdbcClient jdbc;
    private final Mailer mailer;
    private final AuditLog audit;
    private final UserDirectory users;
    private final MembershipService memberships;
    private final RateLimiter perOrganization = new RateLimiter("invitation", 100, Duration.ofHours(1), 10_000);

    InvitationService(JdbcClient jdbc, Mailer mailer, AuditLog audit, UserDirectory users, MembershipService memberships) {
        this.jdbc = jdbc;
        this.mailer = mailer;
        this.audit = audit;
        this.users = users;
        this.memberships = memberships;
    }

    record Invitation(UUID id, String email, OrgRole role, String invitedBy, Instant createdAt, Instant expiresAt) {
    }

    record InvitationPreview(String organizationName, String organizationSlug, String email, OrgRole role,
            String invitedBy, Instant expiresAt, boolean accountExists) {
    }

    @Transactional(readOnly = true)
    List<Invitation> pending(OrgContext ctx) {
        ctx.requireAdmin();
        return jdbc.sql("""
                SELECT i.id, i.email, i.role, u.display_name AS invited_by, i.created_at, i.expires_at
                  FROM invitation i LEFT JOIN user_account u ON u.id = i.invited_by
                 WHERE i.organization_id = :org AND i.accepted_at IS NULL AND i.revoked_at IS NULL
                 ORDER BY i.created_at DESC
                """)
                .param("org", ctx.organizationId())
                .query((rs, n) -> new Invitation(rs.getObject("id", UUID.class), rs.getString("email"),
                        OrgRole.valueOf(rs.getString("role")), rs.getString("invited_by"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    @Transactional
    Invitation invite(OrgContext ctx, String email, OrgRole role) {
        ctx.requireAdmin();
        if (role == OrgRole.OWNER) {
            throw ApiErrors.invalid("role", "Invite as admin, member or guest; promote to owner after joining.");
        }
        if (role == OrgRole.ADMIN && ctx.role() != OrgRole.OWNER) {
            throw ApiErrors.forbidden("Only owners can invite admins.");
        }
        String address = email == null ? "" : email.strip();
        if (!address.matches("^[^@\\s]{1,64}@[^@\\s]{1,255}$") || address.length() > 320) {
            throw ApiErrors.invalid("email", "Enter a valid email address.");
        }
        if (!ctx.settings().allowsEmail(address)) {
            throw ApiErrors.invalid("email", "This organization only allows invitations to: "
                    + String.join(", ", ctx.settings().allowedEmailDomains()));
        }
        perOrganization.acquireOrThrow(ctx.organizationId().toString());
        users.findByEmail(address).ifPresent(existing -> {
            boolean member = jdbc.sql("SELECT count(*) FROM membership WHERE organization_id = :org AND user_id = :user")
                    .param("org", ctx.organizationId()).param("user", existing.id()).query(Long.class).single() > 0;
            if (member) {
                throw ApiErrors.conflict(address + " is already a member.");
            }
        });
        jdbc.sql("""
                UPDATE invitation SET revoked_at = now()
                 WHERE organization_id = :org AND lower(email) = lower(:email) AND accepted_at IS NULL AND revoked_at IS NULL
                """)
                .param("org", ctx.organizationId()).param("email", address).update();
        String token = Tokens.newToken();
        UUID id = Ids.newId();
        OffsetDateTime expires = OffsetDateTime.now().plus(TTL);
        jdbc.sql("""
                INSERT INTO invitation (id, organization_id, email, role, token_hash, invited_by, expires_at)
                VALUES (:id, :org, :email, :role, :hash, :by, :expires)
                """)
                .param("id", id).param("org", ctx.organizationId()).param("email", address).param("role", role.name())
                .param("hash", Tokens.sha256Hex(token)).param("by", ctx.actorId()).param("expires", expires).update();
        sendInvitation(ctx, address, role, token);
        audit.record(AuditEntry.of(ctx.organizationId(), ctx.actorId(), "invitation.created").target("invitation", id)
                .detail("email", address).detail("role", role.name()));
        return new Invitation(id, address, role, ctx.actor().displayName(), Instant.now(), expires.toInstant());
    }

    @Transactional
    void revoke(OrgContext ctx, UUID invitationId) {
        ctx.requireAdmin();
        int updated = jdbc.sql("""
                UPDATE invitation SET revoked_at = now()
                 WHERE id = :id AND organization_id = :org AND accepted_at IS NULL AND revoked_at IS NULL
                """)
                .param("id", invitationId).param("org", ctx.organizationId()).update();
        if (updated == 0) {
            throw ApiErrors.notFound("Invitation");
        }
        audit.record(AuditEntry.of(ctx.organizationId(), ctx.actorId(), "invitation.revoked").target("invitation", invitationId));
    }

    /** Re-sends with a fresh token (the old link stops working). */
    @Transactional
    void resend(OrgContext ctx, UUID invitationId) {
        ctx.requireAdmin();
        perOrganization.acquireOrThrow(ctx.organizationId().toString());
        record Row(String email, OrgRole role) {
        }
        Row row = jdbc.sql("""
                SELECT email, role FROM invitation
                 WHERE id = :id AND organization_id = :org AND accepted_at IS NULL AND revoked_at IS NULL
                """)
                .param("id", invitationId).param("org", ctx.organizationId())
                .query((rs, n) -> new Row(rs.getString("email"), OrgRole.valueOf(rs.getString("role"))))
                .optional().orElseThrow(() -> ApiErrors.notFound("Invitation"));
        String token = Tokens.newToken();
        jdbc.sql("UPDATE invitation SET token_hash = :hash, expires_at = :expires WHERE id = :id")
                .param("hash", Tokens.sha256Hex(token)).param("expires", OffsetDateTime.now().plus(TTL))
                .param("id", invitationId).update();
        sendInvitation(ctx, row.email(), row.role(), token);
    }

    @Transactional(readOnly = true)
    InvitationPreview preview(String token) {
        PendingInvitation invitation = findValid(token);
        boolean exists = users.findByEmail(invitation.email()).isPresent();
        return new InvitationPreview(invitation.organizationName(), invitation.organizationSlug(), invitation.email(),
                invitation.role(), invitation.invitedBy(), invitation.expiresAt(), exists);
    }

    @Transactional
    String acceptAsUser(CurrentUser user, String token) {
        if (user.viaApiToken()) {
            throw ApiErrors.forbidden("Accept invitations from a signed-in session.");
        }
        PendingInvitation invitation = lockValid(token);
        if (!invitation.email().equalsIgnoreCase(user.email())) {
            throw ApiErrors.forbidden("This invitation was sent to a different email address. Sign in with that account to accept it.");
        }
        complete(invitation, user.id());
        return invitation.organizationSlug();
    }

    /** Creates the account (email proven by the token) and joins; the client then signs in. */
    @Transactional
    String acceptAsNewUser(String token, String displayName, String password, Boolean acceptTerms) {
        Consent.require(acceptTerms);
        PendingInvitation invitation = lockValid(token);
        if (users.findByEmail(invitation.email()).isPresent()) {
            throw ApiErrors.conflict("An account already exists for this email address. Sign in to accept the invitation.");
        }
        UUID userId = users.createVerifiedAccount(invitation.email(), displayName, password);
        users.recordConsent(userId);
        complete(invitation, userId);
        return invitation.organizationSlug();
    }

    private void complete(PendingInvitation invitation, UUID userId) {
        memberships.addMember(invitation.organizationId(), userId, invitation.role(), userId);
        jdbc.sql("UPDATE invitation SET accepted_at = now(), accepted_by = :user WHERE id = :id")
                .param("user", userId).param("id", invitation.id()).update();
        audit.record(AuditEntry.of(invitation.organizationId(), userId, "invitation.accepted").target("invitation", invitation.id()));
    }

    private record PendingInvitation(UUID id, UUID organizationId, String organizationName, String organizationSlug,
            String email, OrgRole role, String invitedBy, Instant expiresAt) {
    }

    private PendingInvitation findValid(String token) {
        return query(token, false);
    }

    private PendingInvitation lockValid(String token) {
        return query(token, true);
    }

    private PendingInvitation query(String token, boolean lock) {
        if (token == null || token.isBlank() || token.length() > 200) {
            throw new InvalidInvitation();
        }
        return jdbc.sql("""
                SELECT i.id, i.organization_id, o.name, o.slug, i.email, i.role, u.display_name AS invited_by, i.expires_at
                  FROM invitation i JOIN organization o ON o.id = i.organization_id AND o.deleted_at IS NULL
                  LEFT JOIN user_account u ON u.id = i.invited_by
                 WHERE i.token_hash = :hash AND i.accepted_at IS NULL AND i.revoked_at IS NULL AND i.expires_at > now()
                """ + (lock ? " FOR UPDATE OF i" : ""))
                .param("hash", Tokens.sha256Hex(token))
                .query((rs, n) -> new PendingInvitation(rs.getObject("id", UUID.class),
                        rs.getObject("organization_id", UUID.class), rs.getString("name"), rs.getString("slug"),
                        rs.getString("email"), OrgRole.valueOf(rs.getString("role")), rs.getString("invited_by"),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant()))
                .optional().orElseThrow(InvalidInvitation::new);
    }

    private void sendInvitation(OrgContext ctx, String email, OrgRole role, String token) {
        mailer.send(EmailTemplate.subject("You're invited to " + ctx.name() + " on JTRAC")
                .paragraph(ctx.actor().displayName() + " invited you to join " + ctx.name() + " on JTRAC as "
                        + role.name().toLowerCase(java.util.Locale.ROOT) + ".")
                .action("Accept invitation", mailer.link("/invite?token=" + UriUtils.encode(token, "UTF-8")))
                .footnote("The invitation expires in 7 days. If you weren't expecting it, you can ignore this email.")
                .to(email));
    }

    static final class InvalidInvitation extends ApiProblemException {
        @Serial
        private static final long serialVersionUID = 1L;

        InvalidInvitation() {
            super(HttpStatus.BAD_REQUEST, ProblemTypes.INVALID_TOKEN, "Invalid invitation",
                    "This invitation is invalid, has expired, has been revoked, or was already accepted.");
        }
    }
}
