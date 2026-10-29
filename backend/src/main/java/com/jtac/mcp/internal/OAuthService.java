package com.jtac.mcp.internal;

import com.jtac.audit.AuditEntry;
import com.jtac.audit.AuditLog;
import com.jtac.identity.CurrentUser;
import com.jtac.identity.UserDirectory;
import com.jtac.identity.UserSummary;
import com.jtac.shared.Ids;
import com.jtac.shared.Tokens;
import com.jtac.shared.web.ApiErrors;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
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
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The OAuth 2.1 authorization server for agent connections (MCP authorization profile):
 * public clients only, dynamic client registration (RFC 7591), authorization code with
 * PKCE S256, RFC 8707 resource binding (one connection URL per token), RFC 9207 issuer in
 * the authorization response, rotating refresh tokens with replay detection, and RFC 7009
 * revocation. Tokens are opaque random strings; only their SHA-256 hashes are stored.
 *
 * <p>Consent is given in the signed-in web app by the connection's owner; the agent never
 * sees the user's password or session.
 */
@Service
class OAuthService {

    static final String ACCESS_PREFIX = "jtrac_mcp_";
    static final String REFRESH_PREFIX = "jtrac_mcpr_";
    private static final Pattern VERIFIER = Pattern.compile("[A-Za-z0-9._~-]{43,128}");
    private static final Pattern CHALLENGE = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final JdbcClient jdbc;
    private final ConnectionService connections;
    private final RedirectUris redirects;
    private final McpUrls urls;
    private final McpProperties properties;
    private final UserDirectory users;
    private final AuditLog audit;
    private final ActivityLog activity;
    private final Clock clock;

    OAuthService(JdbcClient jdbc, ConnectionService connections, RedirectUris redirects, McpUrls urls,
            McpProperties properties, UserDirectory users, AuditLog audit, ActivityLog activity, Clock clock) {
        this.jdbc = jdbc;
        this.connections = connections;
        this.redirects = redirects;
        this.urls = urls;
        this.properties = properties;
        this.users = users;
        this.audit = audit;
        this.activity = activity;
        this.clock = clock;
    }

    record Client(String clientId, String clientName, List<String> redirectUris) {
    }

    // --- dynamic client registration (RFC 7591) ---------------------------------------------

    @Transactional
    Map<String, Object> register(Map<String, Object> metadata) {
        Object rawUris = metadata.get("redirect_uris");
        if (!(rawUris instanceof List<?> list) || list.isEmpty() || list.size() > 5) {
            throw new OAuthError(400, "invalid_redirect_uri", "Register 1 to 5 redirect_uris.");
        }
        List<String> uris = new ArrayList<>();
        for (Object value : list) {
            if (!(value instanceof String uri) || redirects.classify(uri).isEmpty()) {
                throw new OAuthError(400, "invalid_redirect_uri", "Redirect URIs must be http://localhost (or 127.0.0.1, [::1])"
                        + " callbacks or HTTPS callbacks allowed by this server, without fragments.");
            }
            uris.add(uri);
        }
        Object method = metadata.get("token_endpoint_auth_method");
        if (method != null && !"none".equals(method)) {
            throw new OAuthError(400, "invalid_client_metadata", "Only public clients are supported (token_endpoint_auth_method none, with PKCE).");
        }
        if (metadata.get("grant_types") instanceof List<?> grants
                && !List.of("authorization_code", "refresh_token").containsAll(grants)) {
            throw new OAuthError(400, "invalid_client_metadata", "Supported grant_types: authorization_code, refresh_token.");
        }
        if (metadata.get("response_types") instanceof List<?> types && !List.of("code").containsAll(types)) {
            throw new OAuthError(400, "invalid_client_metadata", "Supported response_types: code.");
        }
        String name = metadata.get("client_name") instanceof String s ? s.replaceAll("\\p{Cntrl}", " ").strip() : "";
        if (name.isEmpty()) {
            name = "Unnamed agent";
        }
        if (name.length() > 100) {
            name = name.substring(0, 100);
        }
        String clientId = "mcpc_" + Tokens.newToken().substring(0, 32);
        jdbc.sql("INSERT INTO mcp_oauth_client (client_id, client_name, redirect_uris) VALUES (:id, :name, :uris)")
                .param("id", clientId).param("name", name).param("uris", uris.toArray(String[]::new)).update();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("client_id", clientId);
        response.put("client_id_issued_at", clock.instant().getEpochSecond());
        response.put("client_name", name);
        response.put("redirect_uris", uris);
        response.put("grant_types", List.of("authorization_code", "refresh_token"));
        response.put("response_types", List.of("code"));
        response.put("token_endpoint_auth_method", "none");
        return response;
    }

    // --- authorization request --------------------------------------------------------------

    /** Where the browser goes next: the consent screen, or (for errors) the client or an error page. */
    record Redirect(String location) {
    }

    /**
     * Validates an authorization request. Errors that make the redirect URI untrustworthy go
     * to a JTRAC error page; other errors are returned to the client's redirect URI.
     */
    @Transactional
    Redirect authorize(Map<String, String> params) {
        Optional<Client> client = client(params.get("client_id"));
        String redirectUri = params.get("redirect_uri");
        if (client.isEmpty()) {
            return errorPage("unknown_client");
        }
        if (redirectUri == null || !redirects.matches(redirectUri, client.get().redirectUris())) {
            return errorPage("invalid_redirect_uri");
        }
        String state = params.get("state");
        if (state != null && state.length() > 500) {
            return errorPage("invalid_request");
        }
        if (!"code".equals(params.get("response_type"))) {
            return toClient(redirectUri, state, "unsupported_response_type", "Only response_type=code is supported.");
        }
        String challenge = params.get("code_challenge");
        if (!"S256".equals(params.get("code_challenge_method")) || challenge == null || !CHALLENGE.matcher(challenge).matches()) {
            return toClient(redirectUri, state, "invalid_request", "PKCE with code_challenge_method=S256 is required.");
        }
        Optional<String> publicId = urls.publicIdOf(params.get("resource"));
        if (publicId.isEmpty()) {
            return toClient(redirectUri, state, "invalid_target",
                    "The resource parameter must be this connection's MCP URL (RFC 8707).");
        }
        Optional<ConnectionService.Connection> connection = connections.activeByPublicId(publicId.get());
        if (connection.isEmpty()) {
            return toClient(redirectUri, state, "invalid_target", "This connection does not exist or is no longer active.");
        }
        Set<Scope> requested = Scope.parseParameter(params.get("scope"));
        if (requested.isEmpty()) {
            requested = EnumSet.allOf(Scope.class);
        }
        String handle = Tokens.newToken();
        jdbc.sql("""
                INSERT INTO mcp_authorization (id, handle_hash, client_id, connection_id, redirect_uri, state, code_challenge,
                                               requested_scopes, expires_at)
                VALUES (:id, :h, :client, :conn, :redirect, :state, :challenge, :scopes, :expires)
                """)
                .param("id", Ids.newId()).param("h", Tokens.sha256Hex(handle)).param("client", client.get().clientId())
                .param("conn", connection.get().id()).param("redirect", redirectUri).param("state", state)
                .param("challenge", challenge).param("scopes", Scope.wires(requested).toArray(String[]::new))
                .param("expires", utc(clock.instant().plus(properties.authorizationTtl()))).update();
        return new Redirect(urls.endpoint("/connect-agent?request=" + handle));
    }

    // --- consent (signed-in owner, in the web app) -------------------------------------------

    record ScopeView(String name, String description, boolean write) {
    }

    record ConsentView(String clientName, String redirectUri, String redirectHost, String redirectKind, UUID connectionId,
            String connectionName, String organizationName, String organizationSlug, List<ScopeView> granted,
            List<ScopeView> notEnabled, Instant expiresAt) {
    }

    private record Pending(UUID id, String clientId, String clientName, UUID connectionId, String redirectUri, String state,
            Set<Scope> requested, Instant expiresAt) {
    }

    @Transactional(readOnly = true)
    ConsentView describe(CurrentUser user, String handle) {
        Pending pending = pending(handle);
        ConnectionService.Connection connection = ownedActive(user, pending.connectionId());
        Set<Scope> granted = EnumSet.noneOf(Scope.class);
        granted.addAll(pending.requested());
        granted.retainAll(connection.scopes());
        Set<Scope> notEnabled = EnumSet.noneOf(Scope.class);
        notEnabled.addAll(pending.requested());
        notEnabled.removeAll(connection.scopes());
        String kind = redirects.classify(pending.redirectUri()).map(Enum::name).orElse("UNRECOGNISED");
        return new ConsentView(pending.clientName(), pending.redirectUri(), RedirectUris.host(pending.redirectUri()), kind,
                connection.id(), connection.name(), connection.orgName(), connection.orgSlug(), scopeViews(granted),
                scopeViews(notEnabled), pending.expiresAt());
    }

    /** Approves the request and returns the URL that carries the code back to the agent. */
    @Transactional
    String approve(CurrentUser user, String handle) {
        Pending pending = pending(handle);
        ConnectionService.Connection connection = ownedActive(user, pending.connectionId());
        String code = Tokens.newToken();
        int updated = jdbc.sql("""
                UPDATE mcp_authorization SET decided_at = now(), approved_by = :user, code_hash = :code, code_expires_at = :exp
                 WHERE id = :id AND decided_at IS NULL
                """)
                .param("user", user.id()).param("code", Tokens.sha256Hex(code))
                .param("exp", utc(clock.instant().plus(properties.codeTtl()))).param("id", pending.id()).update();
        if (updated == 0) {
            throw ApiErrors.notFound("Authorization request");
        }
        audit.record(AuditEntry.of(connection.organizationId(), user.id(), "mcp.authorization.approved")
                .target("mcp_connection", connection.id()).detail("clientId", pending.clientId())
                .detail("client", pending.clientName()).detail("redirectHost", RedirectUris.host(pending.redirectUri())));
        activity.record(connection.id(), ActivityLog.Kind.AUTH, "authorization approved", ActivityLog.Outcome.OK, null,
                pending.clientName(), null);
        return UriComponentsBuilder.fromUriString(pending.redirectUri()).queryParam("code", code)
                .queryParamIfPresent("state", Optional.ofNullable(pending.state())).queryParam("iss", urls.issuer())
                .encode().build().toUriString();
    }

    @Transactional
    String deny(CurrentUser user, String handle) {
        Pending pending = pending(handle);
        ConnectionService.Connection connection = ownedActive(user, pending.connectionId());
        jdbc.sql("UPDATE mcp_authorization SET decided_at = now() WHERE id = :id AND decided_at IS NULL")
                .param("id", pending.id()).update();
        activity.record(connection.id(), ActivityLog.Kind.AUTH, "authorization denied", ActivityLog.Outcome.DENIED, null,
                pending.clientName(), null);
        return errorUrl(pending.redirectUri(), pending.state(), "access_denied", "The user denied access.");
    }

    // --- token endpoint ------------------------------------------------------------------------

    record TokenResponse(String accessToken, long expiresIn, String refreshToken, String scope) {
    }

    @Transactional(noRollbackFor = OAuthError.class)
    TokenResponse token(Map<String, String> params) {
        String grant = params.get("grant_type");
        if ("authorization_code".equals(grant)) {
            return exchangeCode(params);
        }
        if ("refresh_token".equals(grant)) {
            return refresh(params);
        }
        throw new OAuthError(400, "unsupported_grant_type", "Use authorization_code or refresh_token.");
    }

    private TokenResponse exchangeCode(Map<String, String> params) {
        String code = required(params, "code");
        String verifier = required(params, "code_verifier");
        Client client = client(params.get("client_id")).orElseThrow(() -> OAuthError.invalidClient("Unknown client."));
        record Grant(UUID id, String clientId, UUID connectionId, String redirectUri, String challenge, String[] scopes,
                Instant codeExpires, Instant used, UUID approvedBy) {
        }
        Grant grant = jdbc.sql("""
                SELECT id, client_id, connection_id, redirect_uri, code_challenge, requested_scopes, code_expires_at,
                       code_used_at, approved_by
                  FROM mcp_authorization WHERE code_hash = :h FOR UPDATE
                """)
                .param("h", Tokens.sha256Hex(code))
                .query((rs, n) -> new Grant(rs.getObject("id", UUID.class), rs.getString("client_id"),
                        rs.getObject("connection_id", UUID.class), rs.getString("redirect_uri"), rs.getString("code_challenge"),
                        (String[]) rs.getArray("requested_scopes").getArray(),
                        rs.getObject("code_expires_at", OffsetDateTime.class).toInstant(),
                        Optional.ofNullable(rs.getObject("code_used_at", OffsetDateTime.class)).map(OffsetDateTime::toInstant).orElse(null),
                        rs.getObject("approved_by", UUID.class)))
                .optional().orElseThrow(() -> OAuthError.invalidGrant("Unknown or expired authorization code."));
        if (grant.used() != null) {
            // A replayed code: revoke everything issued from it (OAuth 2.1 section 4.1.3).
            jdbc.sql("UPDATE mcp_token SET revoked_at = now() WHERE family_id = :f AND revoked_at IS NULL")
                    .param("f", grant.id()).update();
            throw OAuthError.invalidGrant("This authorization code was already used.");
        }
        jdbc.sql("UPDATE mcp_authorization SET code_used_at = now() WHERE id = :id").param("id", grant.id()).update();
        if (!grant.codeExpires().isAfter(clock.instant())) {
            throw OAuthError.invalidGrant("The authorization code expired. Sign in again.");
        }
        if (!grant.clientId().equals(client.clientId())) {
            throw OAuthError.invalidGrant("The code was issued to another client.");
        }
        if (!grant.redirectUri().equals(params.get("redirect_uri"))) {
            throw OAuthError.invalidGrant("redirect_uri does not match the authorization request.");
        }
        if (!VERIFIER.matcher(verifier).matches() || !MessageDigest.isEqual(
                s256(verifier).getBytes(StandardCharsets.US_ASCII), grant.challenge().getBytes(StandardCharsets.US_ASCII))) {
            throw OAuthError.invalidGrant("PKCE verification failed.");
        }
        ConnectionService.Connection connection = connections.activeById(grant.connectionId())
                .filter(c -> c.userId().equals(grant.approvedBy()))
                .orElseThrow(() -> OAuthError.invalidGrant("The connection is no longer active."));
        requireResource(params.get("resource"), connection);
        Set<Scope> scopes = Scope.parse(List.of(grant.scopes()));
        audit.record(AuditEntry.of(connection.organizationId(), connection.userId(), "mcp.token.issued")
                .target("mcp_connection", connection.id()).detail("clientId", client.clientId()));
        touchClient(client.clientId());
        return issue(grant.id(), connection, client.clientId(), scopes);
    }

    private TokenResponse refresh(Map<String, String> params) {
        String presented = required(params, "refresh_token");
        Client client = client(params.get("client_id")).orElseThrow(() -> OAuthError.invalidClient("Unknown client."));
        record Refresh(UUID id, UUID familyId, UUID connectionId, String clientId, String[] scopes, Instant expires,
                boolean used, boolean revoked) {
        }
        Refresh token = jdbc.sql("""
                SELECT id, family_id, connection_id, client_id, scopes, expires_at, used_at IS NOT NULL AS used,
                       revoked_at IS NOT NULL AS revoked
                  FROM mcp_token WHERE token_hash = :h AND kind = 'REFRESH' FOR UPDATE
                """)
                .param("h", Tokens.sha256Hex(presented))
                .query((rs, n) -> new Refresh(rs.getObject("id", UUID.class), rs.getObject("family_id", UUID.class),
                        rs.getObject("connection_id", UUID.class), rs.getString("client_id"),
                        (String[]) rs.getArray("scopes").getArray(), rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        rs.getBoolean("used"), rs.getBoolean("revoked")))
                .optional().orElseThrow(() -> OAuthError.invalidGrant("Unknown refresh token."));
        if (!token.clientId().equals(client.clientId())) {
            throw OAuthError.invalidGrant("The refresh token was issued to another client.");
        }
        if (token.used() && !token.revoked()) {
            // Rotation replay: someone holds an old refresh token. End the whole family.
            int revoked = jdbc.sql("UPDATE mcp_token SET revoked_at = now() WHERE family_id = :f AND revoked_at IS NULL")
                    .param("f", token.familyId()).update();
            audit.record(AuditEntry.of(null, null, "mcp.token.replay_detected").target("mcp_connection", token.connectionId())
                    .detail("clientId", client.clientId()).detail("revokedTokens", revoked));
            throw OAuthError.invalidGrant("This refresh token was already used; the agent must sign in again.");
        }
        if (token.revoked() || !token.expires().isAfter(clock.instant())) {
            throw OAuthError.invalidGrant("The refresh token is no longer valid; the agent must sign in again.");
        }
        ConnectionService.Connection connection = connections.activeById(token.connectionId())
                .orElseThrow(() -> OAuthError.invalidGrant("The connection is no longer active."));
        if (!users.find(connection.userId()).map(UserSummary::active).orElse(false)) {
            throw OAuthError.invalidGrant("The account is not active.");
        }
        requireResource(params.get("resource"), connection);
        Set<Scope> scopes = Scope.parse(List.of(token.scopes()));
        String narrowed = params.get("scope");
        if (narrowed != null && !narrowed.isBlank()) {
            Set<Scope> requested = Scope.parseParameter(narrowed);
            if (!scopes.containsAll(requested)) {
                throw new OAuthError(400, "invalid_scope", "A refresh cannot add scopes.");
            }
            scopes = requested;
        }
        jdbc.sql("UPDATE mcp_token SET used_at = now() WHERE id = :id").param("id", token.id()).update();
        touchClient(client.clientId());
        return issue(token.familyId(), connection, client.clientId(), scopes);
    }

    private TokenResponse issue(UUID familyId, ConnectionService.Connection connection, String clientId, Set<Scope> scopes) {
        String access = ACCESS_PREFIX + Tokens.newToken();
        String refresh = REFRESH_PREFIX + Tokens.newToken();
        Instant now = clock.instant();
        insertToken(familyId, connection.id(), clientId, "ACCESS", access, scopes, now.plus(properties.accessTokenTtl()));
        Instant refreshExpiry = now.plus(properties.refreshTokenTtl());
        if (connection.expiresAt() != null && connection.expiresAt().isBefore(refreshExpiry)) {
            refreshExpiry = connection.expiresAt();
        }
        insertToken(familyId, connection.id(), clientId, "REFRESH", refresh, scopes, refreshExpiry);
        return new TokenResponse(access, properties.accessTokenTtl().toSeconds(), refresh, Scope.parameter(scopes));
    }

    private void insertToken(UUID family, UUID connectionId, String clientId, String kind, String secret, Set<Scope> scopes,
            Instant expires) {
        jdbc.sql("""
                INSERT INTO mcp_token (id, family_id, connection_id, client_id, kind, token_hash, scopes, expires_at)
                VALUES (:id, :family, :conn, :client, :kind, :hash, :scopes, :expires)
                """)
                .param("id", Ids.newId()).param("family", family).param("conn", connectionId).param("client", clientId)
                .param("kind", kind).param("hash", Tokens.sha256Hex(secret))
                .param("scopes", Scope.wires(scopes).toArray(String[]::new)).param("expires", utc(expires)).update();
    }

    // --- revocation (RFC 7009) ------------------------------------------------------------------

    /** Revokes the token (a refresh token ends its whole family). Unknown tokens are ignored, as RFC 7009 requires. */
    @Transactional
    void revoke(String token, String clientId) {
        if (token == null || token.isBlank()) {
            throw OAuthError.invalidRequest("token is required.");
        }
        record Row(UUID familyId, String kind, String clientId) {
        }
        Optional<Row> row = jdbc.sql("SELECT family_id, kind, client_id FROM mcp_token WHERE token_hash = :h")
                .param("h", Tokens.sha256Hex(token))
                .query((rs, n) -> new Row(rs.getObject("family_id", UUID.class), rs.getString("kind"), rs.getString("client_id")))
                .optional();
        if (row.isEmpty() || (clientId != null && !clientId.equals(row.get().clientId()))) {
            return;
        }
        if ("REFRESH".equals(row.get().kind())) {
            jdbc.sql("UPDATE mcp_token SET revoked_at = now() WHERE family_id = :f AND revoked_at IS NULL")
                    .param("f", row.get().familyId()).update();
        } else {
            jdbc.sql("UPDATE mcp_token SET revoked_at = now() WHERE token_hash = :h AND revoked_at IS NULL")
                    .param("h", Tokens.sha256Hex(token)).update();
        }
    }

    // --- resource server: access token validation ---------------------------------------------

    /** An authenticated agent request. {@code scopes} = token scopes ∩ the connection's current scopes. */
    record AgentPrincipal(ConnectionService.Connection connection, UserSummary user, Set<Scope> scopes, String clientId,
            String clientName) {
    }

    /**
     * Validates a bearer token presented at {@code /mcp/<publicId>}: known, unexpired, unrevoked,
     * issued for exactly this connection URL (audience), connection active, account active.
     */
    @Transactional(readOnly = true)
    Optional<AgentPrincipal> authenticate(String bearer, String publicId) {
        if (bearer == null || !bearer.startsWith(ACCESS_PREFIX) || bearer.length() > 100) {
            return Optional.empty();
        }
        record Row(UUID connectionId, String clientId, String clientName, String[] scopes) {
        }
        Optional<Row> row = jdbc.sql("""
                SELECT t.connection_id, t.client_id, c.client_name, t.scopes
                  FROM mcp_token t JOIN mcp_oauth_client c ON c.client_id = t.client_id
                 WHERE t.token_hash = :h AND t.kind = 'ACCESS' AND t.revoked_at IS NULL AND t.expires_at > now()
                """)
                .param("h", Tokens.sha256Hex(bearer))
                .query((rs, n) -> new Row(rs.getObject("connection_id", UUID.class), rs.getString("client_id"),
                        rs.getString("client_name"), (String[]) rs.getArray("scopes").getArray()))
                .optional();
        if (row.isEmpty()) {
            return Optional.empty();
        }
        Optional<ConnectionService.Connection> connection = connections.activeById(row.get().connectionId())
                .filter(c -> c.publicId().equals(publicId));
        if (connection.isEmpty()) {
            return Optional.empty();
        }
        Optional<UserSummary> user = users.find(connection.get().userId()).filter(UserSummary::active);
        if (user.isEmpty()) {
            return Optional.empty();
        }
        Set<Scope> scopes = EnumSet.noneOf(Scope.class);
        scopes.addAll(Scope.parse(List.of(row.get().scopes())));
        scopes.retainAll(connection.get().scopes());
        return Optional.of(new AgentPrincipal(connection.get(), user.get(), scopes, row.get().clientId(), row.get().clientName()));
    }

    // --- helpers -----------------------------------------------------------------------------

    private Optional<Client> client(String clientId) {
        if (clientId == null || clientId.length() > 64) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT client_id, client_name, redirect_uris FROM mcp_oauth_client WHERE client_id = :id")
                .param("id", clientId)
                .query((rs, n) -> new Client(rs.getString("client_id"), rs.getString("client_name"),
                        List.of((String[]) rs.getArray("redirect_uris").getArray())))
                .optional();
    }

    private void touchClient(String clientId) {
        jdbc.sql("UPDATE mcp_oauth_client SET last_used_at = now() WHERE client_id = :id").param("id", clientId).update();
    }

    private Pending pending(String handle) {
        if (handle == null || handle.length() > 100) {
            throw ApiErrors.notFound("Authorization request");
        }
        return jdbc.sql("""
                SELECT a.id, a.client_id, c.client_name, a.connection_id, a.redirect_uri, a.state, a.requested_scopes, a.expires_at
                  FROM mcp_authorization a JOIN mcp_oauth_client c ON c.client_id = a.client_id
                 WHERE a.handle_hash = :h AND a.decided_at IS NULL AND a.expires_at > now()
                """)
                .param("h", Tokens.sha256Hex(handle))
                .query((rs, n) -> new Pending(rs.getObject("id", UUID.class), rs.getString("client_id"),
                        rs.getString("client_name"), rs.getObject("connection_id", UUID.class), rs.getString("redirect_uri"),
                        rs.getString("state"), Scope.parse(List.of((String[]) rs.getArray("requested_scopes").getArray())),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant()))
                .optional().orElseThrow(() -> ApiErrors.notFound("Authorization request"));
    }

    /** Only the connection's owner may approve; anyone else learns nothing about the request. */
    private ConnectionService.Connection ownedActive(CurrentUser user, UUID connectionId) {
        if (user.viaApiToken()) {
            throw ApiErrors.forbidden("Approve agent access from a signed-in session.");
        }
        return connections.activeById(connectionId).filter(c -> c.userId().equals(user.id()))
                .orElseThrow(() -> ApiErrors.notFound("Authorization request"));
    }

    private void requireResource(String resource, ConnectionService.Connection connection) {
        // MCP clients must send the resource; if one does not, the token is still bound to
        // the connection the user approved, so the audience cannot widen.
        if (resource != null && !urls.publicIdOf(resource).map(connection.publicId()::equals).orElse(false)) {
            throw new OAuthError(400, "invalid_target", "The resource does not match the authorized connection.");
        }
    }

    private List<ScopeView> scopeViews(Set<Scope> scopes) {
        return scopes.stream().sorted().map(s -> new ScopeView(s.wire(), s.description(), s.write())).toList();
    }

    private Redirect errorPage(String reason) {
        return new Redirect(urls.endpoint("/connect-agent?error=" + reason));
    }

    private Redirect toClient(String redirectUri, String state, String error, String description) {
        return new Redirect(errorUrl(redirectUri, state, error, description));
    }

    private String errorUrl(String redirectUri, String state, String error, String description) {
        return UriComponentsBuilder.fromUriString(redirectUri).queryParam("error", error)
                .queryParam("error_description", description).queryParamIfPresent("state", Optional.ofNullable(state))
                .queryParam("iss", urls.issuer()).encode().build().toUriString();
    }

    private static String required(Map<String, String> params, String name) {
        String value = params.get(name);
        if (value == null || value.isBlank() || value.length() > 200) {
            throw OAuthError.invalidRequest(name + " is required.");
        }
        return value;
    }

    static String s256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
