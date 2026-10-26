package com.jtac.shared.web;

import java.net.URI;

/**
 * Stable RFC 9457 problem type identifiers. Clients branch on {@code type}, never on
 * {@code title} or {@code detail}, which are human-readable and may change.
 * Every type is documented in {@code api/openapi.yaml}.
 */
public final class ProblemTypes {

    public static final URI MALFORMED_REQUEST = of("malformed-request");
    public static final URI VALIDATION_FAILED = of("validation-failed");
    public static final URI UNAUTHENTICATED = of("unauthenticated");
    public static final URI INVALID_CREDENTIALS = of("invalid-credentials");
    public static final URI FORBIDDEN = of("forbidden");
    public static final URI CSRF_REJECTED = of("csrf-rejected");
    public static final URI NOT_FOUND = of("not-found");
    public static final URI METHOD_NOT_ALLOWED = of("method-not-allowed");
    public static final URI UNSUPPORTED_MEDIA_TYPE = of("unsupported-media-type");
    public static final URI CONFLICT = of("conflict");
    public static final URI VERSION_CONFLICT = of("version-conflict");
    public static final URI EMAIL_NOT_VERIFIED = of("email-not-verified");
    public static final URI PAYLOAD_TOO_LARGE = of("payload-too-large");
    public static final URI INVALID_TOKEN = of("invalid-token");
    public static final URI RATE_LIMITED = of("rate-limited");
    public static final URI INTERNAL_ERROR = of("internal-error");

    private ProblemTypes() {
    }

    private static URI of(String name) {
        return URI.create("urn:jtac:problem:" + name);
    }
}
