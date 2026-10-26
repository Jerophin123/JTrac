package com.jtac.shared.web;

import java.io.Serial;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Standard API exceptions used by every module. Each maps to an RFC 9457 problem with a
 * stable type (see {@link ProblemTypes} and {@code api/openapi.yaml}).
 */
public final class ApiErrors {

    private ApiErrors() {
    }

    /** The resource does not exist <em>or the caller may not know it exists</em>. */
    public static NotFound notFound(String what) {
        return new NotFound(what + " was not found.");
    }

    public static Forbidden forbidden(String detail) {
        return new Forbidden(detail);
    }

    public static Conflict conflict(String detail) {
        return new Conflict(detail);
    }

    public static ValidationFailed invalid(String field, String message) {
        return new ValidationFailed(List.of(new FieldError(field, message)));
    }

    public static BadRequest badRequest(String detail) {
        return new BadRequest(detail);
    }

    public record FieldError(String field, String message) {
    }

    public static final class NotFound extends ApiProblemException {
        @Serial
        private static final long serialVersionUID = 1L;

        NotFound(String detail) {
            super(HttpStatus.NOT_FOUND, ProblemTypes.NOT_FOUND, "Not found", detail);
        }
    }

    public static final class Forbidden extends ApiProblemException {
        @Serial
        private static final long serialVersionUID = 1L;

        Forbidden(String detail) {
            super(HttpStatus.FORBIDDEN, ProblemTypes.FORBIDDEN, "Forbidden", detail);
        }
    }

    public static final class Conflict extends ApiProblemException {
        @Serial
        private static final long serialVersionUID = 1L;

        Conflict(String detail) {
            super(HttpStatus.CONFLICT, ProblemTypes.CONFLICT, "Conflict", detail);
        }
    }

    public static final class BadRequest extends ApiProblemException {
        @Serial
        private static final long serialVersionUID = 1L;

        BadRequest(String detail) {
            super(HttpStatus.BAD_REQUEST, ProblemTypes.MALFORMED_REQUEST, "Bad request", detail);
        }
    }

    public static final class ValidationFailed extends ApiProblemException {
        @Serial
        private static final long serialVersionUID = 1L;
        private final transient List<FieldError> errors;

        public ValidationFailed(List<FieldError> errors) {
            super(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION_FAILED, "Validation failed",
                    "One or more fields are invalid.");
            this.errors = List.copyOf(errors);
        }

        public List<FieldError> errors() {
            return errors;
        }

        @Override
        public Map<String, Object> properties() {
            return Map.of("errors", errors.stream()
                    .map(e -> Map.of("field", e.field(), "message", e.message()))
                    .toList());
        }
    }
}
