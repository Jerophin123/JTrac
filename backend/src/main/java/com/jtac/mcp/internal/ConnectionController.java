package com.jtac.mcp.internal;

import com.jtac.identity.CurrentUser;
import com.jtac.shared.web.ApiErrors;
import com.jtac.shared.web.ApiPaths;
import com.jtac.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's AI agent connections and agent sign-in approvals. Everything here
 * requires a browser session: an API token or an agent can never create connections,
 * change their permissions or approve access.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/me")
class ConnectionController {

    private final ConnectionService connections;
    private final OAuthService oauth;
    private final McpUrls urls;
    private final McpProperties properties;

    ConnectionController(ConnectionService connections, OAuthService oauth, McpUrls urls, McpProperties properties) {
        this.connections = connections;
        this.oauth = oauth;
        this.urls = urls;
        this.properties = properties;
    }

    record ScopeOption(String name, String description, boolean write) {
    }

    record ProfileOption(String key, String label, List<String> scopes, boolean isDefault) {
    }

    record Options(boolean available, String unavailableReason, String serverUrl, List<ScopeOption> scopes,
            List<ProfileOption> profiles, int maxConnections) {
    }

    @GetMapping("/agent-connections/options")
    Options options(CurrentUser user) {
        requireSession(user);
        String reason = !properties.enabled() ? "AI agent connections are disabled by the server administrator."
                : !urls.secure() ? "AI agent connections require HTTPS. Ask the administrator to set the public URL to an https:// address."
                : null;
        return new Options(reason == null, reason, urls.issuer(),
                Arrays.stream(Scope.values()).map(s -> new ScopeOption(s.wire(), s.description(), s.write())).toList(),
                Arrays.stream(Scope.Profile.values()).map(p -> new ProfileOption(p.name(), p.label(), Scope.wires(p.scopes()),
                        p == Scope.Profile.DEFAULT)).toList(),
                properties.maxConnectionsPerUser());
    }

    @GetMapping("/agent-connections")
    List<ConnectionService.ConnectionView> list(CurrentUser user) {
        requireSession(user);
        return connections.list(user.id());
    }

    @PostMapping("/agent-connections")
    ResponseEntity<ConnectionService.ConnectionView> create(CurrentUser user, @Valid @RequestBody CreateConnection body) {
        requireEnabled(user);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(connections.create(user, body.name(), body.organizationId(), body.scopes(), body.expiresInDays()));
    }

    @GetMapping("/agent-connections/{id}")
    ConnectionService.ConnectionView get(CurrentUser user, @PathVariable UUID id) {
        requireSession(user);
        return connections.get(user.id(), id);
    }

    @PatchMapping("/agent-connections/{id}")
    ConnectionService.ConnectionView update(CurrentUser user, @PathVariable UUID id, @Valid @RequestBody UpdateConnection body) {
        requireSession(user);
        return connections.update(user.id(), id, body.name(), body.scopes(), body.version());
    }

    @DeleteMapping("/agent-connections/{id}")
    ResponseEntity<Void> delete(CurrentUser user, @PathVariable UUID id) {
        requireSession(user);
        connections.delete(user.id(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/agent-connections/{id}/rotate")
    ConnectionService.ConnectionView rotate(CurrentUser user, @PathVariable UUID id) {
        requireSession(user);
        return connections.rotate(user.id(), id);
    }

    @PostMapping("/agent-connections/{id}/revoke")
    ConnectionService.ConnectionView revoke(CurrentUser user, @PathVariable UUID id) {
        requireSession(user);
        return connections.revoke(user.id(), id);
    }

    @GetMapping("/agent-connections/{id}/agents")
    List<ConnectionService.AuthorizedAgent> agents(CurrentUser user, @PathVariable UUID id) {
        requireSession(user);
        return connections.agents(user.id(), id);
    }

    @DeleteMapping("/agent-connections/{id}/agents/{clientId}")
    ResponseEntity<Void> revokeAgent(CurrentUser user, @PathVariable UUID id, @PathVariable String clientId) {
        requireSession(user);
        connections.revokeAgent(user.id(), id, clientId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/agent-connections/{id}/activity")
    PageResponse<ConnectionService.Activity> activity(CurrentUser user, @PathVariable UUID id,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        requireSession(user);
        return connections.activity(user.id(), id, PageResponse.clampPage(page), PageResponse.clampSize(size, 50));
    }

    // --- agent sign-in approval (the OAuth consent screen) ------------------------------------

    record Decision(String redirectUrl) {
    }

    @GetMapping("/agent-authorizations/{handle}")
    OAuthService.ConsentView consent(CurrentUser user, @PathVariable String handle) {
        requireEnabled(user);
        return oauth.describe(user, handle);
    }

    @PostMapping("/agent-authorizations/{handle}/approve")
    Decision approve(CurrentUser user, @PathVariable String handle) {
        requireEnabled(user);
        return new Decision(oauth.approve(user, handle));
    }

    @PostMapping("/agent-authorizations/{handle}/deny")
    Decision deny(CurrentUser user, @PathVariable String handle) {
        requireEnabled(user);
        return new Decision(oauth.deny(user, handle));
    }

    private static void requireSession(CurrentUser user) {
        if (user.viaApiToken()) {
            throw ApiErrors.forbidden("Manage AI agent connections from a signed-in session.");
        }
    }

    private void requireEnabled(CurrentUser user) {
        requireSession(user);
        if (!properties.enabled() || !urls.secure()) {
            throw ApiErrors.conflict("AI agent connections are not available on this server (disabled, or not served over HTTPS).");
        }
    }

    record CreateConnection(@NotBlank @Size(max = 80) String name, @NotNull UUID organizationId,
            @Size(max = 20) List<String> scopes, Integer expiresInDays) {
    }

    record UpdateConnection(@Size(max = 80) String name, @Size(max = 20) List<String> scopes, long version) {
    }
}
