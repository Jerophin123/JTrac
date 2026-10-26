package com.jtac.identity.internal;

import com.jtac.audit.AuditEntry;
import com.jtac.audit.AuditLog;
import com.jtac.identity.CurrentUser;
import com.jtac.shared.Ids;
import com.jtac.shared.Tokens;
import com.jtac.shared.web.ApiErrors;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Personal API tokens ({@code Authorization: Bearer jtrac_...}). Tokens are shown once at
 * creation; only a SHA-256 hash and a display prefix are stored. A token acts as its user
 * with the user's current permissions, narrowed by its scopes ({@code read}, {@code write})
 * and optionally to one organization.
 */
@Service
class ApiTokenService {

    static final String PREFIX = "jtrac_";
    static final Set<String> SCOPES = Set.of("read", "write");

    private final JdbcClient jdbc;
    private final UserAccountService accounts;
    private final AuditLog audit;
    private final Clock clock;

    ApiTokenService(JdbcClient jdbc, UserAccountService accounts, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.audit = audit;
        this.clock = clock;
    }

    record TokenInfo(UUID id, String name, String prefix, List<String> scopes, UUID organizationId,
            Instant createdAt, Instant lastUsedAt, Instant expiresAt) {
    }

    record CreatedToken(TokenInfo token, String secret) {
    }

    @Transactional
    CreatedToken create(UUID userId, String name, List<String> scopes, UUID organizationId, Instant expiresAt) {
        String label = name == null ? "" : name.strip();
        if (label.isEmpty() || label.length() > 80) {
            throw ApiErrors.invalid("name", "Use 1 to 80 characters.");
        }
        if (scopes == null || scopes.isEmpty() || !SCOPES.containsAll(scopes)) {
            throw ApiErrors.invalid("scopes", "Choose read and/or write.");
        }
        if (expiresAt != null && !expiresAt.isAfter(clock.instant())) {
            throw ApiErrors.invalid("expiresAt", "The expiry must be in the future.");
        }
        String secret = PREFIX + Tokens.newToken();
        UUID id = Ids.newId();
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        List<String> sortedScopes = scopes.stream().distinct().sorted().toList();
        jdbc.sql("""
                INSERT INTO api_token (id, user_id, organization_id, name, token_prefix, token_hash, scopes, created_at, expires_at)
                VALUES (:id, :user, :org, :name, :prefix, :hash, :scopes, :now, :expires)
                """)
                .param("id", id).param("user", userId).param("org", organizationId).param("name", label)
                .param("prefix", secret.substring(0, PREFIX.length() + 6)).param("hash", Tokens.sha256Hex(secret))
                .param("scopes", sortedScopes.toArray(String[]::new)).param("now", now)
                .param("expires", expiresAt == null ? null : expiresAt.atOffset(ZoneOffset.UTC))
                .update();
        audit.record(AuditEntry.of(organizationId, userId, "api_token.created").target("api_token", id)
                .detail("scopes", String.join(",", sortedScopes)));
        TokenInfo info = new TokenInfo(id, label, secret.substring(0, PREFIX.length() + 6), sortedScopes, organizationId,
                now.toInstant(), null, expiresAt);
        return new CreatedToken(info, secret);
    }

    @Transactional(readOnly = true)
    List<TokenInfo> list(UUID userId) {
        return jdbc.sql("""
                SELECT id, name, token_prefix, scopes, organization_id, created_at, last_used_at, expires_at
                  FROM api_token WHERE user_id = :user AND revoked_at IS NULL ORDER BY created_at DESC
                """)
                .param("user", userId)
                .query((rs, n) -> new TokenInfo(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("token_prefix"), List.of((String[]) rs.getArray("scopes").getArray()),
                        rs.getObject("organization_id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        instant(rs.getObject("last_used_at", OffsetDateTime.class)),
                        instant(rs.getObject("expires_at", OffsetDateTime.class))))
                .list();
    }

    @Transactional
    void revoke(UUID userId, UUID tokenId) {
        int updated = jdbc.sql("UPDATE api_token SET revoked_at = now() WHERE id = :id AND user_id = :user AND revoked_at IS NULL")
                .param("id", tokenId).param("user", userId).update();
        if (updated == 0) {
            throw ApiErrors.notFound("API token");
        }
        audit.record(AuditEntry.of(null, userId, "api_token.revoked").target("api_token", tokenId));
    }

    /** Resolves a presented bearer token to its principal, if valid, unexpired, unrevoked and its user active. */
    @Transactional
    Optional<CurrentUser> authenticate(String secret) {
        if (secret == null || !secret.startsWith(PREFIX) || secret.length() > 100) {
            return Optional.empty();
        }
        record Row(UUID id, UUID userId, UUID organizationId, String[] scopes) {
        }
        Optional<Row> row = jdbc.sql("""
                SELECT id, user_id, organization_id, scopes FROM api_token
                 WHERE token_hash = :hash AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > now())
                """)
                .param("hash", Tokens.sha256Hex(secret))
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getObject("organization_id", UUID.class), (String[]) rs.getArray("scopes").getArray()))
                .optional();
        if (row.isEmpty()) {
            return Optional.empty();
        }
        jdbc.sql("UPDATE api_token SET last_used_at = now() WHERE id = :id AND (last_used_at IS NULL OR last_used_at < now() - interval '1 minute')")
                .param("id", row.get().id()).update();
        return accounts.findAccount(row.get().userId())
                .filter(UserAccount::isActive)
                .map(account -> new CurrentUser(account.id(), account.email(), account.displayName(), account.handle(),
                        true, !List.of(row.get().scopes()).contains("write"), row.get().organizationId()));
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
