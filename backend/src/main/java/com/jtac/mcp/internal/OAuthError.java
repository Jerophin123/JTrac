package com.jtac.mcp.internal;

import java.io.Serial;

/**
 * An OAuth error response (RFC 6749 section 5.2, RFC 7591 section 3.2.2, RFC 7009).
 * {@code description} is shown to agents and must never contain secrets.
 */
final class OAuthError extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int status;
    private final String error;

    OAuthError(int status, String error, String description) {
        super(description);
        this.status = status;
        this.error = error;
    }

    static OAuthError invalidRequest(String description) {
        return new OAuthError(400, "invalid_request", description);
    }

    static OAuthError invalidGrant(String description) {
        return new OAuthError(400, "invalid_grant", description);
    }

    static OAuthError invalidClient(String description) {
        return new OAuthError(401, "invalid_client", description);
    }

    int status() {
        return status;
    }

    String error() {
        return error;
    }
}
