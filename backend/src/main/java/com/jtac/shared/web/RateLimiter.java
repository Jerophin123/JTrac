package com.jtac.shared.web;

import java.io.Serial;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * In-memory sliding-window rate limiter for abuse-prone endpoints (password reset,
 * registration, invitations, token use). Each check atomically records the attempt when
 * it is allowed. At most {@code maxKeys} keys are kept (least recently used evicted).
 *
 * <p>Per-instance only: see ADR 0005; a shared store is required for multiple instances.
 */
public final class RateLimiter {

    private final String name;
    private final int limit;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> hits;

    public RateLimiter(String name, int limit, Duration window, int maxKeys) {
        this(name, limit, window, maxKeys, Clock.systemUTC());
    }

    public RateLimiter(String name, int limit, Duration window, int maxKeys, Clock clock) {
        this.name = name;
        this.limit = limit;
        this.window = window;
        this.clock = clock;
        this.hits = new LinkedHashMap<>(256, 0.75f, true) {
            @Serial
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Deque<Instant>> eldest) {
                return size() > maxKeys;
            }
        };
    }

    /** Records an attempt for {@code key}, or returns the time until the next one is allowed. */
    public synchronized Optional<Duration> tryAcquire(String key) {
        Instant now = clock.instant();
        Deque<Instant> deque = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        Instant cutoff = now.minus(window);
        while (!deque.isEmpty() && !deque.peekFirst().isAfter(cutoff)) {
            deque.removeFirst();
        }
        if (deque.size() >= limit) {
            Duration wait = Duration.between(now, deque.peekFirst().plus(window));
            return Optional.of(wait.isNegative() || wait.isZero() ? Duration.ofSeconds(1) : wait);
        }
        deque.addLast(now);
        return Optional.empty();
    }

    /** Like {@link #tryAcquire} but throws a 429 problem when the limit is reached. */
    public void acquireOrThrow(String key) {
        tryAcquire(key).ifPresent(wait -> {
            throw new RateLimited(name, wait);
        });
    }

    public static final class RateLimited extends ApiProblemException {
        @Serial
        private static final long serialVersionUID = 1L;
        private final long retryAfterSeconds;

        public RateLimited(String what, Duration retryAfter) {
            super(HttpStatus.TOO_MANY_REQUESTS, ProblemTypes.RATE_LIMITED, "Too many requests",
                    "Too many " + what + " requests. Try again later.");
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
}
