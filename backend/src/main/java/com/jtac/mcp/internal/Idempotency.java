package com.jtac.mcp.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Retry protection for additive agent writes: a {@code requestId} that was already processed
 * on the same connection returns the first result instead of writing again.
 */
@Component
class Idempotency {

    private final JdbcClient jdbc;

    Idempotency(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The result reference of an earlier call with this request id, if any (for the same tool). */
    Optional<String> previous(UUID connectionId, String requestId, String tool) {
        if (requestId == null) {
            return Optional.empty();
        }
        check(requestId);
        return jdbc.sql("SELECT tool, result_ref FROM mcp_idempotency WHERE connection_id = :c AND request_id = :r")
                .param("c", connectionId).param("r", requestId)
                .query((rs, n) -> {
                    if (!rs.getString("tool").equals(tool)) {
                        throw new Args.Invalid("requestId was already used for " + rs.getString("tool") + ".");
                    }
                    return rs.getString("result_ref");
                }).optional();
    }

    /** Remembers the result within the writing transaction (a concurrent duplicate fails and rolls back). */
    void remember(UUID connectionId, String requestId, String tool, String resultRef) {
        if (requestId == null) {
            return;
        }
        try {
            jdbc.sql("INSERT INTO mcp_idempotency (connection_id, request_id, tool, result_ref) VALUES (:c, :r, :t, :ref)")
                    .param("c", connectionId).param("r", requestId).param("t", tool).param("ref", resultRef).update();
        } catch (DuplicateKeyException e) {
            throw new Args.Invalid("A request with this requestId is already being processed; read the issue again.");
        }
    }

    private static void check(String requestId) {
        if (requestId.isBlank() || requestId.length() > 100) {
            throw new Args.Invalid("requestId must be 1 to 100 characters.");
        }
    }
}
