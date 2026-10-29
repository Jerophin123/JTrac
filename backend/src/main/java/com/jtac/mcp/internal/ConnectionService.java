package com.jtac.mcp.internal;

import com.jtac.audit.AuditEntry;
import com.jtac.audit.AuditLog;
import com.jtac.identity.CurrentUser;
import com.jtac.organization.OrganizationAccess;
import com.jtac.shared.Ids;
import com.jtac.shared.web.ApiErrors;
import com.jtac.shared.web.PageResponse;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Connection lifecycle for their owner. A connection is visible to, and manageable by, its
 * owner only: everyone else gets "not found". Revoking or rotating a connection revokes
 * every token issued for it immediately.
 */
@Service
class ConnectionService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
    private static final List<Integer> EXPIRY_DAYS = List.of(30, 90, 365);

    private final JdbcClient jdbc;
    private final OrganizationAccess organizations;
    private final AuditLog audit;
    private final McpUrls urls;
    private final McpProperties properties;
    private final Clock clock;

    ConnectionService(JdbcClient jdbc, OrganizationAccess organizations, AuditLog audit, McpUrls urls,
            McpProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.organizations = organizations;
        this.audit = audit;
        this.urls = urls;
        this.properties = properties;
        this.clock = clock;
    }

    enum Status {
        ACTIVE, EXPIRED, REVOKED
    }

    /** A stored connection. */
    record Connection(UUID id, String publicId, UUID organizationId, UUID userId, String name, Set<Scope> scopes,
            Instant createdAt, Instant lastUsedAt, String lastClient, Instant expiresAt, Instant rotatedAt,
            Instant revokedAt, long version, String orgSlug, String orgName) {

        Status status(Instant now) {
            if (revokedAt != null) {
                return Status.REVOKED;
            }
            return expiresAt != null && !expiresAt.isAfter(now) ? Status.EXPIRED : Status.ACTIVE;
        }
    }

    record OrgRef(UUID id, String slug, String name) {
    }

    /** What the owner sees. The URL is not a secret but is only shown to the owner. */
    record ConnectionView(UUID id, String name, String url, OrgRef organization, List<String> scopes, String profile,
            Status status, Instant createdAt, Instant lastUsedAt, String lastClient, Instant expiresAt, Instant rotatedAt,
            Instant revokedAt, int authorizedAgents, long version) {
    }

    record AuthorizedAgent(String clientId, String clientName, Instant authorizedAt, Instant lastRefreshedAt) {
    }

    record Activity(Instant at, String kind, String name, String outcome, String target, String client, Integer durationMs) {
    }

    // --- owner operations ----------------------------------------------------------------

    @Transactional
    ConnectionView create(CurrentUser user, String name, UUID organizationId, Collection<String> scopes, Integer expiresInDays) {
        String label = name(name);
        if (organizationId == null || organizations.roleOf(organizationId, user.id()).isEmpty()) {
            throw ApiErrors.invalid("organizationId", "Choose an organization you belong to.");
        }
        Set<Scope> granted = scopes(scopes);
        if (expiresInDays != null && !EXPIRY_DAYS.contains(expiresInDays)) {
            throw ApiErrors.invalid("expiresInDays", "Use 30, 90 or 365 days, or no expiry.");
        }
        long active = jdbc.sql("SELECT count(*) FROM mcp_connection WHERE user_id = :u AND revoked_at IS NULL")
                .param("u", user.id()).query(Long.class).single();
        if (active >= properties.maxConnectionsPerUser()) {
            throw ApiErrors.conflict("You can have at most " + properties.maxConnectionsPerUser()
                    + " active agent connections. Revoke one you no longer use.");
        }
        UUID id = Ids.newId();
        Instant expires = expiresInDays == null ? null : clock.instant().plus(Duration.ofDays(expiresInDays));
        jdbc.sql("""
                INSERT INTO mcp_connection (id, public_id, organization_id, user_id, name, scopes, expires_at)
                VALUES (:id, :pid, :org, :user, :name, :scopes, :expires)
                """)
                .param("id", id).param("pid", newPublicId()).param("org", organizationId).param("user", user.id())
                .param("name", label).param("scopes", Scope.wires(granted).toArray(String[]::new))
                .param("expires", expires == null ? null : OffsetDateTime.ofInstant(expires, java.time.ZoneOffset.UTC))
                .update();
        audit.record(AuditEntry.of(organizationId, user.id(), "mcp.connection.created").target("mcp_connection", id)
                .detail("scopes", Scope.parameter(granted)));
        return view(owned(user.id(), id));
    }

    @Transactional(readOnly = true)
    List<ConnectionView> list(UUID userId) {
        return jdbc.sql(SELECT + " WHERE c.user_id = :u ORDER BY c.revoked_at IS NOT NULL, c.created_at DESC")
                .param("u", userId).query(this::row).list().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    ConnectionView get(UUID userId, UUID id) {
        return view(owned(userId, id));
    }

    @Transactional
    ConnectionView update(UUID userId, UUID id, String name, Collection<String> scopes, long version) {
        Connection current = owned(userId, id);
        if (current.revokedAt() != null) {
            throw ApiErrors.conflict("This connection is revoked. Create a new one instead.");
        }
        String label = name == null ? current.name() : name(name);
        Set<Scope> granted = scopes == null ? current.scopes() : scopes(scopes);
        int updated = jdbc.sql("""
                UPDATE mcp_connection SET name = :name, scopes = :scopes, updated_at = now(), version = version + 1
                 WHERE id = :id AND user_id = :u AND version = :version
                """)
                .param("name", label).param("scopes", Scope.wires(granted).toArray(String[]::new))
                .param("id", id).param("u", userId).param("version", version).update();
        if (updated == 0) {
            throw new OptimisticLockingFailureException("connection changed");
        }
        if (!label.equals(current.name())) {
            audit.record(AuditEntry.of(current.organizationId(), userId, "mcp.connection.renamed").target("mcp_connection", id));
        }
        if (!granted.equals(current.scopes())) {
            audit.record(AuditEntry.of(current.organizationId(), userId, "mcp.connection.permissions_changed")
                    .target("mcp_connection", id).detail("from", Scope.parameter(current.scopes()))
                    .detail("to", Scope.parameter(granted)));
        }
        return view(owned(userId, id));
    }

    /** A new URL; the old one stops working and every agent must sign in again. */
    @Transactional
    ConnectionView rotate(UUID userId, UUID id) {
        Connection current = owned(userId, id);
        if (current.revokedAt() != null) {
            throw ApiErrors.conflict("This connection is revoked. Create a new one instead.");
        }
        jdbc.sql("""
                UPDATE mcp_connection SET public_id = :pid, rotated_at = now(), updated_at = now(), version = version + 1
                 WHERE id = :id AND user_id = :u
                """)
                .param("pid", newPublicId()).param("id", id).param("u", userId).update();
        int tokens = revokeTokens(id, null);
        audit.record(AuditEntry.of(current.organizationId(), userId, "mcp.connection.rotated").target("mcp_connection", id)
                .detail("revokedTokens", tokens));
        return view(owned(userId, id));
    }

    @Transactional
    ConnectionView revoke(UUID userId, UUID id) {
        Connection current = owned(userId, id);
        if (current.revokedAt() == null) {
            jdbc.sql("UPDATE mcp_connection SET revoked_at = now(), updated_at = now(), version = version + 1 WHERE id = :id")
                    .param("id", id).update();
            int tokens = revokeTokens(id, null);
            audit.record(AuditEntry.of(current.organizationId(), userId, "mcp.connection.revoked").target("mcp_connection", id)
                    .detail("revokedTokens", tokens));
        }
        return view(owned(userId, id));
    }

    @Transactional
    void delete(UUID userId, UUID id) {
        Connection current = owned(userId, id);
        jdbc.sql("DELETE FROM mcp_connection WHERE id = :id AND user_id = :u").param("id", id).param("u", userId).update();
        audit.record(AuditEntry.of(current.organizationId(), userId, "mcp.connection.deleted").target("mcp_connection", id));
    }

    @Transactional(readOnly = true)
    List<AuthorizedAgent> agents(UUID userId, UUID id) {
        owned(userId, id);
        return jdbc.sql("""
                SELECT t.client_id, c.client_name, min(t.created_at) AS first_at, max(t.created_at) AS last_at
                  FROM mcp_token t JOIN mcp_oauth_client c ON c.client_id = t.client_id
                 WHERE t.connection_id = :id AND t.kind = 'REFRESH' AND t.revoked_at IS NULL AND t.used_at IS NULL
                   AND t.expires_at > now()
                 GROUP BY t.client_id, c.client_name ORDER BY max(t.created_at) DESC
                """)
                .param("id", id)
                .query((rs, n) -> new AuthorizedAgent(rs.getString("client_id"), rs.getString("client_name"),
                        rs.getObject("first_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("last_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /** Signs one agent out of the connection (its tokens are revoked); others keep working. */
    @Transactional
    void revokeAgent(UUID userId, UUID id, String clientId) {
        Connection current = owned(userId, id);
        int tokens = revokeTokens(id, clientId);
        if (tokens == 0) {
            throw ApiErrors.notFound("Agent");
        }
        audit.record(AuditEntry.of(current.organizationId(), userId, "mcp.agent.revoked").target("mcp_connection", id)
                .detail("clientId", clientId).detail("revokedTokens", tokens));
    }

    @Transactional(readOnly = true)
    PageResponse<Activity> activity(UUID userId, UUID id, int page, int size) {
        owned(userId, id);
        long total = jdbc.sql("SELECT count(*) FROM mcp_activity WHERE connection_id = :id").param("id", id)
                .query(Long.class).single();
        List<Activity> items = jdbc.sql("""
                SELECT occurred_at, kind, name, outcome, target, client, duration_ms FROM mcp_activity
                 WHERE connection_id = :id ORDER BY occurred_at DESC, id DESC LIMIT :limit OFFSET :offset
                """)
                .param("id", id).param("limit", size).param("offset", (long) page * size)
                .query((rs, n) -> new Activity(rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        rs.getString("kind"), rs.getString("name"), rs.getString("outcome"), rs.getString("target"),
                        rs.getString("client"), (Integer) rs.getObject("duration_ms")))
                .list();
        return new PageResponse<>(items, page, size, total);
    }

    // --- for the OAuth server and the MCP endpoint -----------------------------------------

    /** An active (not revoked, not expired) connection by its public id. */
    @Transactional(readOnly = true)
    Optional<Connection> activeByPublicId(String publicId) {
        if (publicId == null || !McpUrls.PUBLIC_ID.matcher(publicId).matches()) {
            return Optional.empty();
        }
        return jdbc.sql(SELECT + " WHERE c.public_id = :pid").param("pid", publicId).query(this::row).optional()
                .filter(c -> c.status(clock.instant()) == Status.ACTIVE);
    }

    @Transactional(readOnly = true)
    Optional<Connection> activeById(UUID id) {
        return jdbc.sql(SELECT + " WHERE c.id = :id").param("id", id).query(this::row).optional()
                .filter(c -> c.status(clock.instant()) == Status.ACTIVE);
    }

    /** Revokes the connection's tokens (of one client, if given); returns how many. */
    int revokeTokens(UUID connectionId, String clientId) {
        return jdbc.sql("""
                UPDATE mcp_token SET revoked_at = now()
                 WHERE connection_id = :id AND revoked_at IS NULL AND (CAST(:client AS text) IS NULL OR client_id = :client)
                """)
                .param("id", connectionId).param("client", clientId).update();
    }

    // --- helpers -------------------------------------------------------------------------

    private static final String SELECT = """
            SELECT c.*, o.slug AS org_slug, o.name AS org_name
              FROM mcp_connection c JOIN organization o ON o.id = c.organization_id AND o.deleted_at IS NULL
            """;

    private Connection owned(UUID userId, UUID id) {
        return jdbc.sql(SELECT + " WHERE c.id = :id AND c.user_id = :u").param("id", id).param("u", userId)
                .query(this::row).optional().orElseThrow(() -> ApiErrors.notFound("Agent connection"));
    }

    private ConnectionView view(Connection c) {
        int agents = jdbc.sql("""
                SELECT count(DISTINCT client_id) FROM mcp_token
                 WHERE connection_id = :id AND kind = 'REFRESH' AND revoked_at IS NULL AND used_at IS NULL AND expires_at > now()
                """)
                .param("id", c.id()).query(Integer.class).single();
        return new ConnectionView(c.id(), c.name(), urls.resource(c.publicId()), new OrgRef(c.organizationId(), c.orgSlug(),
                c.orgName()), Scope.wires(c.scopes()), Scope.Profile.matching(c.scopes()).map(Enum::name).orElse("CUSTOM"),
                c.status(clock.instant()), c.createdAt(), c.lastUsedAt(), c.lastClient(), c.expiresAt(), c.rotatedAt(),
                c.revokedAt(), agents, c.version());
    }

    private Connection row(ResultSet rs, int n) throws SQLException {
        return new Connection(rs.getObject("id", UUID.class), rs.getString("public_id"),
                rs.getObject("organization_id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("name"),
                Scope.parse(List.of((String[]) rs.getArray("scopes").getArray())),
                instant(rs, "created_at"), instant(rs, "last_used_at"), rs.getString("last_client"), instant(rs, "expires_at"),
                instant(rs, "rotated_at"), instant(rs, "revoked_at"), rs.getLong("version"), rs.getString("org_slug"),
                rs.getString("org_name"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String name(String name) {
        String label = name == null ? "" : name.strip();
        if (label.isEmpty() || label.length() > 80) {
            throw ApiErrors.invalid("name", "Use 1 to 80 characters.");
        }
        return label;
    }

    private static Set<Scope> scopes(Collection<String> wires) {
        if (wires == null || wires.isEmpty()) {
            return Scope.Profile.DEFAULT.scopes();
        }
        Set<Scope> parsed = Scope.parse(wires);
        if (parsed.size() != Set.copyOf(wires).size()) {
            throw ApiErrors.invalid("scopes", "Unknown permission. Use the values listed in the options.");
        }
        return parsed;
    }

    /** {@code c_} and 192 random bits, URL-safe; unrelated to the user, organization or any sequence. */
    static String newPublicId() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return "c_" + URL.encodeToString(bytes);
    }
}
