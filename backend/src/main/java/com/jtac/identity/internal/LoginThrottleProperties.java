package com.jtac.identity.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Brute-force protection for the sign-in endpoint. Unsuccessful attempts are counted
 * within a sliding window per (account, client) pair, per client address, and per
 * account across all clients. The per-account ceiling is deliberately much higher than
 * the per-pair limit so that one client cannot lock another user out cheaply.
 */
@ConfigurationProperties("jtac.security.login-throttle")
record LoginThrottleProperties(
        @DefaultValue("5") int maxFailuresPerAccountAndClient,
        @DefaultValue("30") int maxFailuresPerClient,
        @DefaultValue("50") int maxFailuresPerAccount,
        @DefaultValue("15m") Duration window,
        @DefaultValue("100000") int maxTrackedKeys) {
}
