package com.jtac.mcp.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * OAuth redirect URI policy (MCP authorization, OAuth 2.1 and RFC 8252):
 * <ul>
 *   <li>loopback {@code http} callbacks ({@code localhost}, {@code 127.0.0.1}, {@code [::1]})
 *       are always allowed, and their port is ignored when matching because local agents
 *       listen on a port chosen at run time;</li>
 *   <li>{@code https} callbacks of hosted agents follow {@link McpProperties#httpsRedirects()};</li>
 *   <li>anything else (plain http to another host, custom schemes, fragments, user info)
 *       is rejected.</li>
 * </ul>
 * Matching is otherwise exact: scheme, host, path and query must equal a registered URI.
 */
@Component
class RedirectUris {

    enum Kind {
        /** A program on the user's own computer. */
        LOOPBACK,
        /** A hosted agent whose callback host is in {@code jtac.mcp.trusted-redirect-hosts}. */
        RECOGNISED,
        /** Another HTTPS callback: allowed only when the policy is ANY, with a warning on the consent screen. */
        UNRECOGNISED
    }

    private final McpProperties properties;

    RedirectUris(McpProperties properties) {
        this.properties = properties;
    }

    /** The kind of a URI an agent wants to register, or empty if it is not allowed. */
    Optional<Kind> classify(String redirectUri) {
        Optional<URI> parsed = parse(redirectUri);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        URI uri = parsed.get();
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("http")) {
            return McpUrls.isLoopbackHost(uri.getHost()) ? Optional.of(Kind.LOOPBACK) : Optional.empty();
        }
        if (!scheme.equals("https")) {
            return Optional.empty();
        }
        boolean trusted = properties.trustedRedirectHosts().stream()
                .anyMatch(h -> h.equalsIgnoreCase(uri.getHost()));
        return switch (properties.httpsRedirects()) {
            case NONE -> Optional.empty();
            case ALLOWLIST -> trusted ? Optional.of(Kind.RECOGNISED) : Optional.empty();
            case ANY -> Optional.of(trusted ? Kind.RECOGNISED : Kind.UNRECOGNISED);
        };
    }

    /** Whether {@code presented} matches one of the client's registered URIs (and is still allowed). */
    boolean matches(String presented, Collection<String> registered) {
        if (classify(presented).isEmpty()) {
            return false;
        }
        URI p = parse(presented).orElseThrow();
        for (String candidate : registered) {
            Optional<URI> r = parse(candidate);
            if (r.isEmpty()) {
                continue;
            }
            URI c = r.get();
            boolean loopback = c.getScheme().equalsIgnoreCase("http") && McpUrls.isLoopbackHost(c.getHost());
            boolean same = c.getScheme().equalsIgnoreCase(p.getScheme()) && c.getHost().equalsIgnoreCase(p.getHost())
                    && (loopback || c.getPort() == p.getPort())
                    && nullToEmpty(c.getRawPath()).equals(nullToEmpty(p.getRawPath()))
                    && nullToEmpty(c.getRawQuery()).equals(nullToEmpty(p.getRawQuery()));
            if (same) {
                return true;
            }
        }
        return false;
    }

    static String host(String redirectUri) {
        return parse(redirectUri).map(URI::getHost).orElse("");
    }

    private static Optional<URI> parse(String value) {
        if (value == null || value.isBlank() || value.length() > 2000) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(value);
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
                return Optional.empty();
            }
            return Optional.of(uri);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
