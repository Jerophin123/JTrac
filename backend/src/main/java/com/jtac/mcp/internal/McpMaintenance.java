package com.jtac.mcp.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes expired OAuth state, old activity, and dynamically registered clients that never
 * completed a sign-in (registration is anonymous, so unused clients must not accumulate).
 * Runs only when background jobs are enabled ({@code jtac.jobs.enabled}).
 */
@Component
class McpMaintenance {

    private static final Logger log = LoggerFactory.getLogger(McpMaintenance.class);

    private final JdbcClient jdbc;
    private final McpProperties properties;

    McpMaintenance(JdbcClient jdbc, McpProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Scheduled(cron = "${jtac.mcp.cleanup-cron:0 41 3 * * *}")
    @Transactional
    public void cleanUp() {
        int authorizations = jdbc.sql("DELETE FROM mcp_authorization WHERE expires_at < now() - interval '1 day'").update();
        int tokens = jdbc.sql("DELETE FROM mcp_token WHERE expires_at < now() - interval '7 days'").update();
        int activity = jdbc.sql("DELETE FROM mcp_activity WHERE occurred_at < now() - make_interval(secs => :s)")
                .param("s", (double) properties.activityRetention().toSeconds()).update();
        int idempotency = jdbc.sql("DELETE FROM mcp_idempotency WHERE created_at < now() - interval '7 days'").update();
        int clients = jdbc.sql("""
                DELETE FROM mcp_oauth_client c
                 WHERE c.created_at < now() - interval '1 day' AND c.last_used_at IS NULL
                   AND NOT EXISTS (SELECT 1 FROM mcp_authorization a WHERE a.client_id = c.client_id)
                """).update();
        log.info("MCP cleanup: authorizations={} tokens={} activity={} idempotency={} clients={}", authorizations, tokens,
                activity, idempotency, clients);
    }
}
