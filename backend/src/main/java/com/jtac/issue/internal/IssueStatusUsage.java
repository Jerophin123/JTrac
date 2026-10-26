package com.jtac.issue.internal;

import com.jtac.workflow.StatusUsage;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Implements the workflow module's SPI without depending back on it (no bean cycle). */
@Component
class IssueStatusUsage implements StatusUsage {

    private final JdbcClient jdbc;

    IssueStatusUsage(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Long> issueCountsByStatus(UUID projectId) {
        Map<UUID, Long> counts = new HashMap<>();
        jdbc.sql("SELECT status_id, count(*) AS n FROM issue WHERE project_id = :p GROUP BY status_id")
                .param("p", projectId)
                .query((rs, n) -> Map.entry(rs.getObject("status_id", UUID.class), rs.getLong("n")))
                .list().forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }
}
