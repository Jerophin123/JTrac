package com.jtac.mail.internal;

import com.jtac.mail.EmailMessage;
import com.jtrac.serverconfig.Redaction;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers queued email. Rows are claimed with {@code FOR UPDATE SKIP LOCKED}, so several
 * instances can run the dispatcher safely; failed deliveries are retried with
 * exponential backoff (1 min, 2 min, 4 min … capped at 6 h) until {@code maxAttempts}.
 */
@Component
public class MailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MailDispatcher.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final MailTransport transport;
    private final MailRuntime runtime;

    MailDispatcher(JdbcClient jdbc, TransactionTemplate tx, MailTransport transport, MailRuntime runtime) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.transport = transport;
        this.runtime = runtime;
    }

    @Scheduled(fixedDelayString = "${jtac.mail.poll-interval:5s}", initialDelayString = "${jtac.mail.poll-interval:5s}")
    void scheduledDispatch() {
        dispatchDue();
    }

    /**
     * Delivers due messages; returns how many were processed. In email-pending mode nothing is
     * sent and messages stay queued until SMTP is configured. Public for operational use and tests.
     */
    public int dispatchDue() {
        MailRuntime.Current current = runtime.current();
        if (current.transport() == MailRuntime.Transport.DISABLED) {
            return 0;
        }
        Integer processed = tx.execute(status -> {
            List<Row> rows = jdbc.sql("""
                    SELECT id, to_address, subject, text_body, html_body, attempts
                      FROM email_outbox
                     WHERE status = 'PENDING' AND next_attempt_at <= now()
                     ORDER BY next_attempt_at
                     LIMIT :batch
                     FOR UPDATE SKIP LOCKED
                    """)
                    .param("batch", current.batchSize())
                    .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getInt("attempts"),
                            new EmailMessage(rs.getString("to_address"), rs.getString("subject"),
                                    rs.getString("text_body"), rs.getString("html_body"))))
                    .list();
            for (Row row : rows) {
                deliver(row, current.smtp().maxAttempts());
            }
            return rows.size();
        });
        return processed == null ? 0 : processed;
    }

    private void deliver(Row row, int maxAttempts) {
        try {
            transport.deliver(row.id(), row.message());
            jdbc.sql("UPDATE email_outbox SET status = 'SENT', sent_at = now(), attempts = attempts + 1, last_error = NULL WHERE id = :id")
                    .param("id", row.id()).update();
        } catch (Exception ex) {
            int attempts = row.attempts() + 1;
            boolean giveUp = attempts >= maxAttempts;
            Duration backoff = Duration.ofMinutes(Math.min(360, 1L << Math.min(attempts - 1, 9)));
            log.warn("Email delivery failed (attempt {} of {}): {}", attempts, maxAttempts, ex.getClass().getSimpleName());
            jdbc.sql("""
                    UPDATE email_outbox
                       SET attempts = :attempts, status = :status, last_error = :error, next_attempt_at = :next
                     WHERE id = :id
                    """)
                    .param("attempts", attempts)
                    .param("status", giveUp ? "FAILED" : "PENDING")
                    .param("error", truncate(ex.getClass().getSimpleName() + ": " + Redaction.scrub(ex.getMessage(), current().password())))
                    .param("next", OffsetDateTime.now().plus(backoff))
                    .param("id", row.id())
                    .update();
        }
    }

    private MailRuntime.Current current() {
        return runtime.current();
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 500 ? value : value.substring(0, 500);
    }

    private record Row(UUID id, int attempts, EmailMessage message) {
    }
}
