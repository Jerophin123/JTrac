package com.jtac.identity.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * In-memory, per-instance sign-in throttle (sliding window).
 *
 * <p>Every attempt is <em>reserved</em> atomically before credentials are checked
 * ({@link #tryAcquire}); a successful sign-in releases its reservation
 * ({@link #succeeded}). Concurrent attempts therefore cannot all pass the check before
 * any failure is recorded.
 *
 * <p>Memory is bounded: at most {@code maxTrackedKeys} keys are kept (least recently
 * used keys are evicted first). Limitations (not shared between instances, reset on
 * restart) are documented in ADR 0005; a shared store replaces this in M2.
 */
@Component
class LoginThrottle {

    private final LoginThrottleProperties properties;
    private final Clock clock;
    private final Map<String, Deque<Instant>> attempts;

    @Autowired
    LoginThrottle(LoginThrottleProperties properties) {
        this(properties, Clock.systemUTC());
    }

    LoginThrottle(LoginThrottleProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.attempts = new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Deque<Instant>> eldest) {
                return size() > properties.maxTrackedKeys();
            }
        };
    }

    /**
     * Reserves one attempt for this account and client, or returns how long the caller
     * must wait if any limit is reached (in which case nothing is reserved).
     */
    synchronized Optional<Duration> tryAcquire(String accountKey, String clientKey) {
        Instant now = clock.instant();
        Duration wait = Duration.ZERO;
        wait = max(wait, retryAfter(pairKey(accountKey, clientKey), properties.maxFailuresPerAccountAndClient(), now));
        wait = max(wait, retryAfter(clientKey(clientKey), properties.maxFailuresPerClient(), now));
        wait = max(wait, retryAfter(accountKey(accountKey), properties.maxFailuresPerAccount(), now));
        if (!wait.isZero()) {
            return Optional.of(wait);
        }
        append(pairKey(accountKey, clientKey), now);
        append(clientKey(clientKey), now);
        append(accountKey(accountKey), now);
        return Optional.empty();
    }

    /** Releases the reservation of a successful attempt and clears the pair's failures. */
    synchronized void succeeded(String accountKey, String clientKey) {
        attempts.remove(pairKey(accountKey, clientKey));
        removeNewest(clientKey(clientKey));
        removeNewest(accountKey(accountKey));
    }

    private Duration retryAfter(String key, int limit, Instant now) {
        Deque<Instant> window = attempts.get(key);
        if (window == null) {
            return Duration.ZERO;
        }
        prune(window, now);
        if (window.isEmpty()) {
            attempts.remove(key);
            return Duration.ZERO;
        }
        if (window.size() < limit) {
            return Duration.ZERO;
        }
        Duration wait = Duration.between(now, window.peekFirst().plus(properties.window()));
        return wait.isNegative() || wait.isZero() ? Duration.ofSeconds(1) : wait;
    }

    private void append(String key, Instant now) {
        attempts.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(now);
    }

    private void removeNewest(String key) {
        Deque<Instant> window = attempts.get(key);
        if (window != null) {
            window.pollLast();
            if (window.isEmpty()) {
                attempts.remove(key);
            }
        }
    }

    private void prune(Deque<Instant> window, Instant now) {
        Instant cutoff = now.minus(properties.window());
        while (!window.isEmpty() && !window.peekFirst().isAfter(cutoff)) {
            window.removeFirst();
        }
    }

    synchronized int trackedKeys() {
        return attempts.size();
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    private static String pairKey(String account, String client) {
        return "pair:" + account + "|" + client;
    }

    private static String clientKey(String client) {
        return "client:" + client;
    }

    private static String accountKey(String account) {
        return "account:" + account;
    }
}
