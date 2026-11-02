package com.jtac.utcp.internal;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Generates canonical public URLs for UTCP discovery manuals and tool execution endpoints.
 */
@Component
class UtcpUrls {

    private final String baseUrl;

    UtcpUrls(@Value("${jtac.public-url}") String publicUrl) {
        this.baseUrl = canonical(publicUrl).orElse("http://localhost:5173");
    }

    String baseUrl() {
        return baseUrl;
    }

    String baseUrl(HttpServletRequest request) {
        if (request != null) {
            String forwardHost = request.getHeader("X-Forwarded-Host");
            if (forwardHost != null && !forwardHost.isBlank()) {
                String forwardProto = request.getHeader("X-Forwarded-Proto");
                String scheme = (forwardProto != null && !forwardProto.isBlank()) ? forwardProto : request.getScheme();
                return scheme + "://" + forwardHost.strip();
            }
            String scheme = request.getScheme();
            String host = request.getServerName();
            int port = request.getServerPort();
            boolean isDefaultPort = ("http".equalsIgnoreCase(scheme) && port == 80) || ("https".equalsIgnoreCase(scheme) && port == 443);
            return scheme + "://" + host + (isDefaultPort ? "" : ":" + port);
        }
        return baseUrl;
    }

    String manualUrl() {
        return baseUrl + "/api/v1/utcp";
    }

    String toolUrl(String toolName) {
        return baseUrl + "/api/v1/utcp/tools/" + toolName;
    }

    String toolUrl(String toolName, String baseOverride) {
        String effectiveBase = (baseOverride != null && !baseOverride.isBlank()) ? baseOverride : baseUrl;
        return effectiveBase + "/api/v1/utcp/tools/" + toolName;
    }

    String executeUrl() {
        return baseUrl + "/api/v1/utcp/execute";
    }

    static Optional<String> canonical(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        try {
            URI u = new URI(url.strip());
            if (u.getScheme() == null || u.getHost() == null) {
                return Optional.empty();
            }
            String scheme = u.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return Optional.empty();
            }
            int defaultPort = scheme.equals("https") ? 443 : 80;
            int port = u.getPort() == -1 || u.getPort() == defaultPort ? -1 : u.getPort();
            String path = u.getPath() == null ? "" : u.getPath().replaceAll("/+$", "");
            URI canonical = new URI(scheme, null, u.getHost().toLowerCase(Locale.ROOT), port, path, null, null);
            return Optional.of(canonical.toASCIIString());
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}

