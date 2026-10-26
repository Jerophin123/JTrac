package com.jtac.integration.internal;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param secretKey             base64-encoded 256-bit key encrypting webhook secrets
 *                              ({@code JTAC_SECRET_KEY}); required when {@code requireSecretKey}
 * @param secretKeyFile         development fallback: the key is generated into this file
 * @param requireSecretKey      refuse to start without {@code secretKey} (production)
 * @param allowPrivateNetworks  allow webhook targets on loopback/private/link-local addresses
 *                              (development and tests only: enables SSRF against internal services)
 * @param allowHttp             allow plain-http webhook URLs (development and tests only)
 * @param maxAttempts           delivery attempts before a delivery is marked FAILED
 * @param timeout               connect and response timeout per attempt
 * @param batchSize             deliveries claimed per dispatcher run
 */
@ConfigurationProperties("jtac.integrations")
record IntegrationProperties(
        String secretKey,
        @DefaultValue("./data/secret.key") Path secretKeyFile,
        @DefaultValue("false") boolean requireSecretKey,
        @DefaultValue("false") boolean allowPrivateNetworks,
        @DefaultValue("false") boolean allowHttp,
        @DefaultValue("8") int maxAttempts,
        @DefaultValue("10s") Duration timeout,
        @DefaultValue("20") int batchSize) {
}
