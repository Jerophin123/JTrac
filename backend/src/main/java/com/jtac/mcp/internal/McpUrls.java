package com.jtac.mcp.internal;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Externally visible URLs, derived from {@code jtac.public-url} (never from request headers,
 * so a forged Host header cannot change the issuer or a token's audience).
 *
 * <p>The issuer is the public URL without a trailing slash; each connection is its own
 * OAuth protected resource, {@code <issuer>/mcp/<publicId>}.
 */
@Component
class McpUrls {

    static final Pattern PUBLIC_ID = Pattern.compile("c_[A-Za-z0-9_-]{32}");

    private final String issuer;
    private final boolean secure;

    McpUrls(@Value("${jtac.public-url}") String publicUrl) {
        this.issuer = canonical(publicUrl).orElseThrow(() -> new IllegalStateException("jtac.public-url is not a valid URL"));
        URI uri = URI.create(issuer);
        this.secure = "https".equals(uri.getScheme()) || isLoopbackHost(uri.getHost());
    }

    String issuer() {
        return issuer;
    }

    /**
     * Whether OAuth may run here: over HTTPS, or on a loopback address for local development.
     * Tokens must never travel over plain HTTP between machines.
     */
    boolean secure() {
        return secure;
    }

    String resource(String publicId) {
        return issuer + "/mcp/" + publicId;
    }

    String resourceMetadata(String publicId) {
        return issuer + "/.well-known/oauth-protected-resource/mcp/" + publicId;
    }

    String endpoint(String path) {
        return issuer + path;
    }

    String issueLink(String orgSlug, String issueKey) {
        return issuer + "/" + orgSlug + "/issue/" + issueKey;
    }

    /**
     * The connection public id named by an RFC 8707 {@code resource} parameter, if it is one of
     * this server's connection URLs (scheme and host compared case-insensitively).
     */
    Optional<String> publicIdOf(String resource) {
        return canonical(resource).filter(r -> r.startsWith(issuer + "/mcp/"))
                .map(r -> r.substring(issuer.length() + 5))
                .filter(id -> PUBLIC_ID.matcher(id).matches());
    }

    /** Lower-case scheme and host, default port removed, no trailing slash, no query or fragment. */
    static Optional<String> canonical(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(url.strip());
            if (uri.getScheme() == null || uri.getHost() == null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getRawUserInfo() != null) {
                return Optional.empty();
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !scheme.equals("http")) {
                return Optional.empty();
            }
            int port = uri.getPort();
            boolean defaultPort = port == -1 || (scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80);
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            return Optional.of(scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port) + path);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost")) {
            return true;
        }
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        // Literal addresses only: a host name other than "localhost" is never resolved here.
        if (!h.matches("[0-9.]+") && !h.matches("[0-9a-f:]+")) {
            return false;
        }
        try {
            return InetAddress.getByName(h).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
