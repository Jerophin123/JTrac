package com.jtac.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class LoginThrottleTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-24T10:00:00Z"));
    private final LoginThrottle throttle = new LoginThrottle(
            new LoginThrottleProperties(3, 5, 10, Duration.ofMinutes(15), 1_000), clock);

    private void fail(String account, String client) {
        assertThat(throttle.tryAcquire(account, client)).isEmpty();
    }

    @Test
    void allowsAttemptsBelowThePairLimit() {
        fail("a@example.com", "10.0.0.1");
        fail("a@example.com", "10.0.0.1");
        assertThat(throttle.tryAcquire("a@example.com", "10.0.0.1")).isEmpty();
    }

    @Test
    void blocksAPairAtTheLimitUntilTheOldestAttemptLeavesTheWindow() {
        for (int i = 0; i < 3; i++) {
            fail("a@example.com", "10.0.0.1");
            clock.advance(Duration.ofMinutes(1));
        }
        assertThat(throttle.tryAcquire("a@example.com", "10.0.0.1")).contains(Duration.ofMinutes(12));

        clock.advance(Duration.ofMinutes(12));
        assertThat(throttle.tryAcquire("a@example.com", "10.0.0.1")).isEmpty();
    }

    @Test
    void anotherClientsFailuresDoNotLockTheAccountOutBelowTheAccountCeiling() {
        for (int i = 0; i < 3; i++) {
            fail("victim@example.com", "203.0.113.7");
        }
        assertThat(throttle.tryAcquire("victim@example.com", "203.0.113.7")).isPresent();
        assertThat(throttle.tryAcquire("victim@example.com", "10.0.0.1")).as("the real user elsewhere").isEmpty();
    }

    @Test
    void enforcesAnAccountCeilingAcrossClients() {
        for (int i = 0; i < 10; i++) {
            fail("target@example.com", "198.51.100." + i);
        }
        assertThat(throttle.tryAcquire("target@example.com", "198.51.100.200")).isPresent();
    }

    @Test
    void blocksAClientThatSpraysManyAccounts() {
        for (int i = 0; i < 5; i++) {
            fail("user" + i + "@example.com", "10.0.0.1");
        }
        assertThat(throttle.tryAcquire("fresh@example.com", "10.0.0.1")).isPresent();
        assertThat(throttle.tryAcquire("fresh@example.com", "10.0.0.2")).isEmpty();
    }

    @Test
    void successReleasesTheReservationAndClearsThePair() {
        fail("a@example.com", "10.0.0.1");
        fail("a@example.com", "10.0.0.1");
        assertThat(throttle.tryAcquire("a@example.com", "10.0.0.1")).isEmpty();
        throttle.succeeded("a@example.com", "10.0.0.1");
        fail("a@example.com", "10.0.0.1");
        fail("a@example.com", "10.0.0.1");
        assertThat(throttle.tryAcquire("a@example.com", "10.0.0.1")).isEmpty();
    }

    @Test
    void concurrentAttemptsCannotExceedTheLimit() throws Exception {
        int threads = 50;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return throttle.tryAcquire("a@example.com", "10.0.0.1").isEmpty();
                }));
            }
            start.countDown();
            int admitted = 0;
            for (Future<Boolean> result : results) {
                admitted += result.get() ? 1 : 0;
            }
            assertThat(admitted).isEqualTo(3);
        }
    }

    @Test
    void trackedKeysAreBounded() {
        for (int i = 0; i < 5_000; i++) {
            throttle.tryAcquire("user" + i + "@example.com", "client-" + i);
        }
        assertThat(throttle.trackedKeys()).isLessThanOrEqualTo(1_000);
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
