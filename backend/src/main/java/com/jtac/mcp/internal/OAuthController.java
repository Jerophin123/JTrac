package com.jtac.mcp.internal;

import com.jtac.shared.web.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OAuth endpoints for agent connections. These are not part of {@code /api/v1}: their shapes
 * are fixed by RFC 8414 (authorization server metadata), RFC 9728 (protected resource
 * metadata), RFC 7591 (registration), RFC 6749/OAuth 2.1 (authorize, token) and RFC 7009
 * (revocation), and they answer with OAuth error objects, not RFC 9457 problems.
 */
@RestController
class OAuthController {

    private final OAuthService oauth;
    private final McpUrls urls;
    private final McpProperties properties;
    private final RateLimiter registrations;
    private final RateLimiter authorizations = new RateLimiter("authorization", 60, Duration.ofMinutes(1), 10_000);
    private final RateLimiter tokens = new RateLimiter("token", 120, Duration.ofMinutes(1), 10_000);

    OAuthController(OAuthService oauth, McpUrls urls, McpProperties properties) {
        this.oauth = oauth;
        this.urls = urls;
        this.properties = properties;
        this.registrations = new RateLimiter("client registration", properties.registrationsPerHour(), Duration.ofHours(1), 10_000);
    }

    @GetMapping("/.well-known/oauth-authorization-server")
    ResponseEntity<Map<String, Object>> authorizationServerMetadata() {
        requireAvailable();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("issuer", urls.issuer());
        body.put("authorization_endpoint", urls.endpoint("/oauth/authorize"));
        body.put("token_endpoint", urls.endpoint("/oauth/token"));
        body.put("registration_endpoint", urls.endpoint("/oauth/register"));
        body.put("revocation_endpoint", urls.endpoint("/oauth/revoke"));
        body.put("response_types_supported", List.of("code"));
        body.put("response_modes_supported", List.of("query"));
        body.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
        body.put("code_challenge_methods_supported", List.of("S256"));
        body.put("token_endpoint_auth_methods_supported", List.of("none"));
        body.put("revocation_endpoint_auth_methods_supported", List.of("none"));
        body.put("scopes_supported", Scope.wires(List.of(Scope.values())));
        body.put("authorization_response_iss_parameter_supported", true);
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1))).body(body);
    }

    /** RFC 9728 metadata for one connection URL. Served for any well-formed id, so it reveals nothing. */
    @GetMapping("/.well-known/oauth-protected-resource/mcp/{publicId}")
    ResponseEntity<Map<String, Object>> protectedResourceMetadata(@PathVariable String publicId) {
        requireAvailable();
        if (!McpUrls.PUBLIC_ID.matcher(publicId).matches()) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resource", urls.resource(publicId));
        body.put("authorization_servers", List.of(urls.issuer()));
        body.put("scopes_supported", Scope.wires(List.of(Scope.values())));
        body.put("bearer_methods_supported", List.of("header"));
        body.put("resource_name", "JTRAC");
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1))).body(body);
    }

    @PostMapping(path = "/oauth/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, Object> metadata, HttpServletRequest request) {
        requireAvailable();
        limit(registrations, request.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(oauth.register(metadata));
    }

    @GetMapping("/oauth/authorize")
    ResponseEntity<Void> authorize(@RequestParam Map<String, String> params, HttpServletRequest request) {
        requireAvailable();
        limit(authorizations, request.getRemoteAddr());
        OAuthService.Redirect next = oauth.authorize(params);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(next.location()))
                .cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping(path = "/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<Map<String, Object>> token(@RequestParam MultiValueMap<String, String> form, HttpServletRequest request) {
        requireAvailable();
        limit(tokens, request.getRemoteAddr());
        OAuthService.TokenResponse token = oauth.token(single(form));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", token.accessToken());
        body.put("token_type", "Bearer");
        body.put("expires_in", token.expiresIn());
        body.put("refresh_token", token.refreshToken());
        body.put("scope", token.scope());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache").body(body);
    }

    @PostMapping(path = "/oauth/revoke", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<Void> revoke(@RequestParam MultiValueMap<String, String> form, HttpServletRequest request) {
        requireAvailable();
        limit(tokens, request.getRemoteAddr());
        Map<String, String> params = single(form);
        oauth.revoke(params.get("token"), params.get("client_id"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler(OAuthError.class)
    ResponseEntity<Map<String, Object>> oauthError(OAuthError error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error.error());
        body.put("error_description", error.getMessage());
        return ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore()).body(body);
    }

    private void requireAvailable() {
        if (!properties.enabled()) {
            throw new OAuthError(404, "not_found", "AI agent connections are disabled on this server.");
        }
        if (!urls.secure()) {
            throw new OAuthError(503, "temporarily_unavailable",
                    "AI agent connections require HTTPS. Set jtac.public-url to an https:// address.");
        }
    }

    private static void limit(RateLimiter limiter, String key) {
        Optional<Duration> wait = limiter.tryAcquire(key);
        if (wait.isPresent()) {
            throw new OAuthError(429, "slow_down", "Too many requests; retry in " + Math.max(1, wait.get().toSeconds()) + " s.");
        }
    }

    /** Form parameters must not repeat (RFC 6749 section 3.1). */
    private static Map<String, String> single(MultiValueMap<String, String> form) {
        Map<String, String> result = new LinkedHashMap<>();
        form.forEach((key, values) -> {
            if (values.size() > 1) {
                throw OAuthError.invalidRequest("Parameter " + key + " was sent more than once.");
            }
            result.put(key, values.isEmpty() ? null : values.getFirst());
        });
        return result;
    }
}
