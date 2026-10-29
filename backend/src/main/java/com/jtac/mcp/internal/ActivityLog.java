package com.jtac.mcp.internal;

import com.jtac.shared.Ids;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records what agents do through a connection, for the owner's activity view and for
 * monitoring. Only names, outcomes and issue/project keys are stored: never arguments,
 * issue content or tokens. Runs in its own transaction so a failed tool call is still logged.
 */
@Component
class ActivityLog {

    enum Kind {
        TOOL, RESOURCE, PROMPT, AUTH
    }

    enum Outcome {
        OK, ERROR, DENIED, RATE_LIMITED
    }

    private final JdbcClient jdbc;

    ActivityLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void record(UUID connectionId, Kind kind, String name, Outcome outcome, String target, String client, Integer durationMs) {
        jdbc.sql("""
                INSERT INTO mcp_activity (id, connection_id, kind, name, outcome, target, client, duration_ms)
                VALUES (:id, :c, :kind, :name, :outcome, :target, :client, :ms)
                """)
                .param("id", Ids.newId()).param("c", connectionId).param("kind", kind.name()).param("name", clip(name))
                .param("outcome", outcome.name()).param("target", clip(target)).param("client", clip(client))
                .param("ms", durationMs).update();
        if (outcome == Outcome.OK) {
            // "Last used" means a successful, authorized call (at most one write per minute).
            jdbc.sql("""
                    UPDATE mcp_connection SET last_used_at = now(), last_client = coalesce(:client, last_client)
                     WHERE id = :c AND (last_used_at IS NULL OR last_used_at < now() - interval '1 minute'
                           OR last_client IS DISTINCT FROM coalesce(:client, last_client))
                    """)
                    .param("c", connectionId).param("client", clip(client)).update();
        }
    }

    private static String clip(String value) {
        if (value == null) {
            return null;
        }
        String flat = value.replaceAll("\\p{Cntrl}", " ").strip();
        return flat.length() <= 100 ? flat : flat.substring(0, 100);
    }
}
