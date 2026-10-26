package com.jtac.shared.web;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Centralized RFC 9457 error handling. Every error leaving the API is an
 * {@code application/problem+json} document with a stable {@code type} URN, a
 * {@code requestId} for support, and no internal details (stack traces, SQL, class names).
 *
 * <p>Spring Security's entry point and access-denied handler delegate here too
 * (see {@link ProblemSecurityHandlers}), so authentication and CSRF failures use the same format.
 */
@RestControllerAdvice
public class GlobalProblemHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalProblemHandler.class);

    @ExceptionHandler(ApiProblemException.class)
    ResponseEntity<ProblemDetail> handleApiProblem(ApiProblemException ex) {
        ProblemDetail problem = problem(ex.status(), ex.type(), ex.title(), ex.getMessage());
        ex.properties().forEach(problem::setProperty);
        return ResponseEntity.status(ex.status()).headers(ex.headers()).body(problem);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        ProblemDetail problem = ex instanceof BadCredentialsException
                ? problem(HttpStatus.UNAUTHORIZED, ProblemTypes.INVALID_CREDENTIALS, "Invalid credentials",
                        "The email address or password is incorrect.")
                : problem(HttpStatus.UNAUTHORIZED, ProblemTypes.UNAUTHENTICATED, "Authentication required",
                        "Sign in to access this resource.");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail problem = ex instanceof CsrfException
                ? problem(HttpStatus.FORBIDDEN, ProblemTypes.CSRF_REJECTED, "CSRF token missing or invalid",
                        "Refresh the CSRF token (GET /api/v1/auth/csrf) and retry the request.")
                : problem(HttpStatus.FORBIDDEN, ProblemTypes.FORBIDDEN, "Forbidden",
                        "You do not have permission to perform this action.");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    /** Concurrent modification detected by an entity {@code @Version} check. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(HttpStatus.CONFLICT, ProblemTypes.VERSION_CONFLICT,
                "Edited by someone else", "This item was changed by someone else. Reload it and try again."));
    }

    /** A database constraint rejected the change (e.g. a duplicate key). Never echo SQL details. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException ex) {
        log.info("Constraint violation: {}", ex.getMostSpecificCause().getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(HttpStatus.CONFLICT, ProblemTypes.CONFLICT,
                "Conflict", "The change conflicts with existing data."));
    }

    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(problem(HttpStatus.CONTENT_TOO_LARGE,
                ProblemTypes.PAYLOAD_TOO_LARGE, "Upload too large", "The file exceeds the maximum upload size."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem(HttpStatus.INTERNAL_SERVER_ERROR,
                ProblemTypes.INTERNAL_ERROR, "Internal error",
                "An unexpected error occurred. Quote the requestId when reporting this problem."));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION_FAILED, "Validation failed",
                "One or more fields are invalid.");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(e -> e.getField()))
                .map(e -> Map.of("field", e.getField(),
                        "message", e.getDefaultMessage() == null ? "is invalid" : e.getDefaultMessage()))
                .toList();
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().headers(headers).body(problem);
    }

    /** Normalizes the problems Spring MVC creates for its own exceptions (404, 405, 415, unreadable body…). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ProblemDetail problem = body instanceof ProblemDetail detail ? detail : ProblemDetail.forStatus(statusCode);
        if (problem.getType() == null || URI.create("about:blank").equals(problem.getType())) {
            problem.setType(typeFor(statusCode));
        }
        if (statusCode.value() == HttpStatus.BAD_REQUEST.value()) {
            // Never echo parser internals (class names, offsets) back to the client.
            problem.setDetail("The request body or parameters could not be read.");
        }
        problem.setProperty("requestId", RequestIdFilter.current());
        return ResponseEntity.status(statusCode).headers(headers).body(problem);
    }

    private static URI typeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> ProblemTypes.MALFORMED_REQUEST;
            case 401 -> ProblemTypes.UNAUTHENTICATED;
            case 403 -> ProblemTypes.FORBIDDEN;
            case 404 -> ProblemTypes.NOT_FOUND;
            case 405 -> ProblemTypes.METHOD_NOT_ALLOWED;
            case 409 -> ProblemTypes.CONFLICT;
            case 413 -> ProblemTypes.PAYLOAD_TOO_LARGE;
            case 415 -> ProblemTypes.UNSUPPORTED_MEDIA_TYPE;
            case 429 -> ProblemTypes.RATE_LIMITED;
            default -> status.is5xxServerError() ? ProblemTypes.INTERNAL_ERROR : URI.create("about:blank");
        };
    }

    static ProblemDetail problem(HttpStatus status, URI type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(title);
        problem.setProperty("requestId", RequestIdFilter.current());
        return problem;
    }
}
