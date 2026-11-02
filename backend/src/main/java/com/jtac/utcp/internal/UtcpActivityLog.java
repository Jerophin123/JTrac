package com.jtac.utcp.internal;

import com.jtac.shared.Ids;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records UTCP tool executions for security audits and connection monitoring.
 * Stores tool name, outcome, target and client; never stores passwords, tokens or secrets.
 */
@Component
class UtcpActivityLog {

    enum Outcome {
        OK, ERROR, DENIED, RATE_LIMITED
    }

    private final JdbcClient jdbc;

    UtcpActivityLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void record(UUID userId, UUID organizationId, String toolName, Outcome outcome, String target, String client, Integer durationMs) {
        jdbc.sql("""
                INSERT INTO utcp_activity (id, user_id, organization_id, tool_name, outcome, target, client, duration_ms)
                VALUES (:id, :u, :org, :tool, :outcome, :target, :client, :ms)
                """)
                .param("id", Ids.newId())
                .param("u", userId)
                .param("org", organizationId)
                .param("tool", clip(toolName, 100))
                .param("outcome", outcome.name())
                .param("target", clip(target, 100))
                .param("client", clip(client, 100))
                .param("ms", durationMs)
                .update();

        if (outcome == Outcome.OK) {
            jdbc.sql("""
                    UPDATE utcp_setting
                       SET last_used_at = now(),
                           last_client = coalesce(:client, last_client)
                     WHERE user_id = :u AND organization_id = :org
                       AND (last_used_at IS NULL OR last_used_at < now() - interval '1 minute'
                            OR last_client IS DISTINCT FROM coalesce(:client, last_client))
                    """)
                    .param("u", userId)
                    .param("org", organizationId)
                    .param("client", clip(client, 100))
                    .update();
        }
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        String clean = value.replaceAll("\\p{Cntrl}", " ").strip();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }
}

