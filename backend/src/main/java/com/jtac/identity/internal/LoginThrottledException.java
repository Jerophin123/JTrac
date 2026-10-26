package com.jtac.identity.internal;

import com.jtac.shared.web.ApiProblemException;
import com.jtac.shared.web.ProblemTypes;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

final class LoginThrottledException extends ApiProblemException {

    private static final long serialVersionUID = 1L;

    private final long retryAfterSeconds;

    LoginThrottledException(Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, ProblemTypes.RATE_LIMITED, "Too many sign-in attempts",
                "Too many failed sign-in attempts. Try again later.");
        this.retryAfterSeconds = Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }

    @Override
    public Map<String, Object> properties() {
        return Map.of("retryAfterSeconds", retryAfterSeconds);
    }

    @Override
    public HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        return headers;
    }
}
