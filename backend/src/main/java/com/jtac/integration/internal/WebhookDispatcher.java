package com.jtac.integration.internal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Delivers queued webhook events.
 *
 * <p>Each run leases due deliveries ({@code FOR UPDATE SKIP LOCKED}, then moves their
 * {@code next_attempt_at} past the attempt timeout) in a short transaction and sends them
 * outside any transaction, so several instances can run concurrently and a crashed instance's
 * deliveries are retried once the lease expires.
 *
 * <p>Requests carry {@code X-JTAC-Event}, {@code X-JTAC-Delivery}, {@code X-JTAC-Timestamp}
 * and {@code X-JTAC-Signature: sha256=<hex HMAC-SHA256 of "timestamp.body">}. Redirects are
 * not followed. 2xx is success; anything else is retried with backoff (1 min doubling, capped
 * at 6 h) until {@code max-attempts}, then marked FAILED.
 */
@Component
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    private static final int MAX_ERROR = 500;

    private final JdbcClient jdbc;
    private final SecretBox secrets;
    private final WebhookTargets targets;
    private final IntegrationProperties properties;
    private final HttpClient client;

    WebhookDispatcher(JdbcClient jdbc, SecretBox secrets, WebhookTargets targets, IntegrationProperties properties) {
        this.jdbc = jdbc;
        this.secrets = secrets;
        this.targets = targets;
        this.properties = properties;
        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Scheduled(fixedDelayString = "${jtac.integrations.poll-interval:5s}", initialDelayString = "${jtac.integrations.poll-interval:5s}")
    void scheduledDispatch() {
        dispatchDue();
    }

    /** Sends due deliveries; returns how many were attempted. Public for operations and tests. */
    public int dispatchDue() {
        Duration lease = properties.timeout().multipliedBy(3).plusSeconds(30);
        List<Claimed> claimed = jdbc.sql("""
                WITH due AS (
                    SELECT d.id FROM webhook_delivery d
                     WHERE d.status = 'PENDING' AND d.next_attempt_at <= now()
                     ORDER BY d.next_attempt_at
                     LIMIT :batch
                     FOR UPDATE SKIP LOCKED)
                UPDATE webhook_delivery d SET next_attempt_at = now() + CAST(:lease AS interval)
                  FROM due, webhook w
                 WHERE d.id = due.id AND w.id = d.webhook_id
                RETURNING d.id, d.webhook_id, d.event_id, d.event_type, d.payload::text AS payload, d.attempts,
                          w.url, w.secret_ciphertext, w.active
                """)
                .param("batch", properties.batchSize())
                .param("lease", lease.toSeconds() + " seconds")
                .query((rs, n) -> new Claimed(rs.getObject("id", UUID.class), rs.getObject("webhook_id", UUID.class),
                        rs.getObject("event_id", UUID.class), rs.getString("event_type"), rs.getString("payload"),
                        rs.getInt("attempts"), rs.getString("url"), rs.getString("secret_ciphertext"), rs.getBoolean("active")))
                .list();
        for (Claimed delivery : claimed) {
            send(delivery);
        }
        return claimed.size();
    }

    private void send(Claimed d) {
        if (!d.active() && !"ping".equals(d.eventType())) {
            finish(d, "FAILED", d.attempts(), null, "Webhook is disabled.", null, false);
            return;
        }
        int attempt = d.attempts() + 1;
        long started = System.nanoTime();
        try {
            URI uri = targets.checkForDelivery(d.url());
            String secret = secrets.decrypt(d.secretCiphertext(), d.webhookId().toString());
            String timestamp = Long.toString(Instant.now().getEpochSecond());
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(properties.timeout())
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "JTRAC-Webhooks/1")
                    .header("X-JTRAC-Event", d.eventType())
                    .header("X-JTRAC-Delivery", d.id().toString())
                    .header("X-JTRAC-Timestamp", timestamp)
                    .header("X-JTRAC-Signature", "sha256=" + sign(secret, timestamp + "." + d.payload()))
                    .header("X-JTAC-Event", d.eventType())
                    .header("X-JTAC-Delivery", d.id().toString())
                    .header("X-JTAC-Timestamp", timestamp)
                    .header("X-JTAC-Signature", "sha256=" + sign(secret, timestamp + "." + d.payload()))
                    .POST(HttpRequest.BodyPublishers.ofString(d.payload(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int duration = elapsedMs(started);
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                finish(d, "SUCCEEDED", attempt, response.statusCode(), null, duration, true);
            } else {
                retryOrFail(d, attempt, response.statusCode(), "HTTP " + response.statusCode(), duration);
            }
        } catch (WebhookTargets.Rejected e) {
            finish(d, "FAILED", attempt, null, "Target rejected: " + e.getMessage(), null, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            retryOrFail(d, attempt, null, "Interrupted", elapsedMs(started));
        } catch (Exception e) {
            retryOrFail(d, attempt, null, e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()),
                    elapsedMs(started));
        }
    }

    private void retryOrFail(Claimed d, int attempt, Integer statusCode, String error, Integer duration) {
        if (attempt >= properties.maxAttempts()) {
            log.warn("Webhook delivery {} failed permanently after {} attempts: {}", d.id(), attempt, error);
            finish(d, "FAILED", attempt, statusCode, error, duration, false);
            return;
        }
        Duration backoff = Duration.ofMinutes(Math.min(360, 1L << Math.min(attempt - 1, 9)));
        jdbc.sql("""
                UPDATE webhook_delivery SET attempts = :attempts, last_status_code = :code, last_error = :error,
                       last_duration_ms = :duration, next_attempt_at = :next
                 WHERE id = :id AND status = 'PENDING'
                """)
                .param("attempts", attempt).param("code", statusCode).param("error", truncate(error))
                .param("duration", duration).param("next", OffsetDateTime.now(ZoneOffset.UTC).plus(backoff))
                .param("id", d.id()).update();
    }

    private void finish(Claimed d, String status, int attempts, Integer statusCode, String error, Integer duration,
            boolean delivered) {
        jdbc.sql("""
                UPDATE webhook_delivery SET status = :status, attempts = :attempts, last_status_code = :code,
                       last_error = :error, last_duration_ms = :duration,
                       delivered_at = CASE WHEN :delivered THEN now() END
                 WHERE id = :id AND status = 'PENDING'
                """)
                .param("status", status).param("attempts", attempts).param("code", statusCode)
                .param("error", truncate(error)).param("duration", duration).param("delivered", delivered)
                .param("id", d.id()).update();
    }

    static String sign(String secret, String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static int elapsedMs(long startedNanos) {
        return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private static String truncate(String value) {
        return value == null || value.length() <= MAX_ERROR ? value : value.substring(0, MAX_ERROR);
    }

    private record Claimed(UUID id, UUID webhookId, UUID eventId, String eventType, String payload, int attempts,
            String url, String secretCiphertext, boolean active) {
    }
}
