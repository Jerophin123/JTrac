package com.jtac.shared.web;

import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * Base class for domain and application errors that map to a specific RFC 9457
 * problem response. Modules subclass it instead of building HTTP responses themselves.
 */
public abstract class ApiProblemException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final URI type;
    private final String title;

    protected ApiProblemException(HttpStatus status, URI type, String title, String detail) {
        super(detail);
        this.status = status;
        this.type = type;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public URI type() {
        return type;
    }

    public String title() {
        return title;
    }

    /** Additional problem members (must be safe to expose to clients). */
    public Map<String, Object> properties() {
        return Map.of();
    }

    /** Additional response headers, e.g. {@code Retry-After}. */
    public HttpHeaders headers() {
        return HttpHeaders.EMPTY;
    }
}
