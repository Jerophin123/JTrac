package com.jtac.mcp.internal;

import com.jtac.identity.CurrentUser;
import com.jtac.identity.UserSummary;
import com.jtac.organization.OrgContext;
import com.jtac.organization.OrganizationAccess;
import com.jtac.shared.web.ApiProblemException;
import com.jtac.shared.web.RateLimiter;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.ProtocolVersions;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The MCP Streamable HTTP endpoint, one URL per connection ({@code /mcp/c_<id>}), served
 * statelessly: every request carries its own bearer token and is authorized from scratch.
 *
 * <p>Order of checks: the endpoint is enabled and served securely; the Origin (if any) is
 * this server (DNS-rebinding protection); the access token is valid and was issued for
 * exactly this URL; the account is active; the user is still a member of the connection's
 * organization; the connection's rate limit; the protocol version. Only then does the MCP
 * SDK see the request. Tools re-check project permissions and field rules on every call.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class McpEndpoint {

    static final Set<String> PROTOCOL_VERSIONS = Set.of(ProtocolVersions.MCP_2025_03_26, ProtocolVersions.MCP_2025_06_18,
            ProtocolVersions.MCP_2025_11_25);

    private final OAuthService oauth;
    private final OrganizationAccess organizations;
    private final McpUrls urls;
    private final McpProperties properties;
    private final ActivityLog activity;
    private final HttpServletStatelessServerTransport transport;
    private final RateLimiter perConnection;

    McpEndpoint(OAuthService oauth, OrganizationAccess organizations, McpUrls urls, McpProperties properties,
            ActivityLog activity, HttpServletStatelessServerTransport transport, McpStatelessSyncServer server) {
        this.oauth = oauth;
        this.organizations = organizations;
        this.urls = urls;
        this.properties = properties;
        this.activity = activity;
        this.transport = transport; // the server (built in McpServerConfiguration) is attached to it
        this.perConnection = new RateLimiter("MCP", properties.requestsPerMinute(), Duration.ofMinutes(1), 50_000);
    }

    @RequestMapping("/mcp/{publicId}")
    void handle(@PathVariable String publicId, HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        if (!properties.enabled() || !McpUrls.PUBLIC_ID.matcher(publicId).matches()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (!urls.secure()) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "MCP requires HTTPS");
            return;
        }
        if (!originAllowed(request.getHeader(HttpHeaders.ORIGIN))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Origin not allowed");
            return;
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        boolean bearer = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7);
        Optional<OAuthService.AgentPrincipal> principal = bearer
                ? oauth.authenticate(authorization.substring(7).strip(), publicId) : Optional.empty();
        Optional<OrgContext> org = principal.flatMap(this::organization);
        if (org.isEmpty()) {
            challenge(response, publicId, bearer);
            return;
        }
        OAuthService.AgentPrincipal agent = principal.get();
        Optional<Duration> wait = perConnection.tryAcquire(agent.connection().id().toString());
        if (wait.isPresent()) {
            activity.record(agent.connection().id(), ActivityLog.Kind.TOOL, "rate limit", ActivityLog.Outcome.RATE_LIMITED,
                    null, agent.clientName(), null);
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, wait.get().toSeconds())));
            response.sendError(429, "Too many requests for this connection");
            return;
        }
        String version = request.getHeader("MCP-Protocol-Version");
        if (version != null && !PROTOCOL_VERSIONS.contains(version)) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unsupported MCP-Protocol-Version");
            return;
        }
        if (!"POST".equals(request.getMethod())) {
            // Stateless server: no server-initiated SSE stream (GET) and no sessions to end (DELETE).
            response.setHeader(HttpHeaders.ALLOW, "POST");
            response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        request.setAttribute(AgentRequest.KEY, new AgentRequest(agent, org.get()));
        transport.service(new EndpointRequest(request), response);
    }

    /** The user's organization context for this request, if they are still an active member. */
    private Optional<OrgContext> organization(OAuthService.AgentPrincipal agent) {
        UserSummary u = agent.user();
        boolean write = agent.scopes().stream().anyMatch(Scope::write);
        CurrentUser user = new CurrentUser(u.id(), u.email(), u.displayName(), u.handle(), true, !write,
                agent.connection().organizationId());
        try {
            return organizations.slugOf(agent.connection().organizationId()).map(slug -> organizations.require(user, slug));
        } catch (ApiProblemException e) {
            return Optional.empty();
        }
    }

    private boolean originAllowed(String origin) {
        if (origin == null) {
            return true; // agents are not browsers; browsers always send Origin on cross-origin POSTs
        }
        URI issuer = URI.create(urls.issuer());
        return McpUrls.canonical(origin).map(o -> {
            URI u = URI.create(o);
            return u.getScheme().equals(issuer.getScheme()) && u.getHost().equals(issuer.getHost())
                    && u.getPort() == issuer.getPort();
        }).orElse(false);
    }

    private void challenge(HttpServletResponse response, String publicId, boolean tokenPresented) throws IOException {
        String header = "Bearer resource_metadata=\"" + urls.resourceMetadata(publicId) + "\", scope=\""
                + Scope.parameter(java.util.List.of(Scope.values())) + "\""
                + (tokenPresented ? ", error=\"invalid_token\", error_description=\"The access token is invalid, expired,"
                        + " revoked, or not issued for this connection.\"" : "");
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, header);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + (tokenPresented ? "invalid_token" : "unauthorized") + "\"}");
    }

    /** Presents the request to the SDK transport at its fixed endpoint path. */
    private static final class EndpointRequest extends HttpServletRequestWrapper {
        EndpointRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getRequestURI() {
            return McpServerConfiguration.ENDPOINT;
        }
    }
}
