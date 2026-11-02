package com.jtac.identity.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Browser-facing SSO addresses, derived from {@code jtac.public-url} rather than request
 * headers, so the identity provider's redirect URI and the post-sign-in redirects are the
 * address users open (also through the development proxy or a reverse proxy).
 */
@Component
class SsoUrls {

    private static final int MAX_RETURN_PATH = 2048;

    private final String base;

    SsoUrls(@Value("${jtac.public-url}") String publicUrl) {
        this.base = publicUrl.strip().replaceAll("/+$", "");
    }

    /** Where the identity provider sends the user back (register this URI at the provider). */
    String callback(String registrationId) {
        return base + "/login/oauth2/code/" + registrationId;
    }

    String page(String path) {
        return base + path;
    }

    /**
     * {@code value} if it is a same-origin path the user may return to after sign-in,
     * otherwise {@code null}. Mirrors {@code safeRedirect} in the frontend.
     */
    static String safeReturnPath(String value) {
        if (value == null || value.length() > MAX_RETURN_PATH || !value.startsWith("/")
                || value.startsWith("//") || value.contains("\\") || value.chars().anyMatch(Character::isISOControl)) {
            return null;
        }
        String path = value.split("[?#]", 2)[0];
        return path.equals("/login") || path.startsWith("/oauth2/") || path.startsWith("/login/oauth2/") ? null : value;
    }
}
