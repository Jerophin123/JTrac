package com.jtac.mcp.internal;

import com.jtac.audit.AuditEntry;
import com.jtac.audit.AuditLog;
import com.jtac.collaboration.IssueComments;
import com.jtac.issue.IssueAccess;
import com.jtac.issue.IssueHandle;
import com.jtac.project.ProjectPermission;
import com.jtac.shared.Ids;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fix reports and verification records written by agents. They record what the agent (or
 * developer) reported; JTRAC runs nothing and never marks a report as independently verified.
 * Reading needs the issue to be browsable; writing needs the COMMENT permission on it.
 */
@Service
class FixReports {

    static final String PROVENANCE = "Reported by an AI agent through a JTRAC agent connection. JTRAC did not run these"
            + " tests or inspect the code; treat the contents as the agent's claims.";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final IssueAccess issues;
    private final IssueComments comments;
    private final AuditLog audit;
    private final Idempotency idempotency;

    FixReports(JdbcClient jdbc, JsonMapper json, IssueAccess issues, IssueComments comments, AuditLog audit,
            Idempotency idempotency) {
        this.jdbc = jdbc;
        this.json = json;
        this.issues = issues;
        this.comments = comments;
        this.audit = audit;
        this.idempotency = idempotency;
    }

    record NewReport(String summary, String rootCause, Map<String, Object> details) {
    }

    record Created(UUID reportId, UUID commentId, boolean duplicate) {
    }

    @Transactional
    Created create(AgentRequest request, IssueHandle issue, NewReport report, boolean postComment, String requestId) {
        issue.project().require(ProjectPermission.COMMENT);
        var previous = idempotency.previous(request.connectionId(), requestId, "create_fix_report");
        if (previous.isPresent()) {
            return new Created(UUID.fromString(previous.get()), null, true);
        }
        UUID id = Ids.newId();
        UUID commentId = null;
        if (postComment) {
            commentId = comments.add(issue, commentText(report, request.clientName())).id();
        }
        jdbc.sql("""
                INSERT INTO mcp_fix_report (id, organization_id, issue_id, connection_id, author_id, summary, root_cause, details,
                                            comment_id)
                VALUES (:id, :org, :issue, :conn, :author, :summary, :cause, CAST(:details AS jsonb), :comment)
                """)
                .param("id", id).param("org", issue.organizationId()).param("issue", issue.issueId())
                .param("conn", request.connectionId()).param("author", request.user().id()).param("summary", report.summary())
                .param("cause", report.rootCause()).param("details", json.writeValueAsString(report.details()))
                .param("comment", commentId).update();
        idempotency.remember(request.connectionId(), requestId, "create_fix_report", id.toString());
        audit.record(AuditEntry.of(issue.organizationId(), request.user().id(), "mcp.fix_report.created")
                .target("issue", issue.issueId()).detail("issueKey", issue.key()).detail("reportId", id)
                .detail("connectionId", request.connectionId()));
        return new Created(id, commentId, false);
    }

    @Transactional(readOnly = true)
    List<Map<String, Object>> list(IssueHandle issue) {
        return jdbc.sql("""
                SELECT r.id, r.summary, r.created_at, u.display_name, c.name AS connection_name,
                       (SELECT count(*) FROM mcp_verification v WHERE v.report_id = r.id) AS verifications
                  FROM mcp_fix_report r LEFT JOIN user_account u ON u.id = r.author_id
                  LEFT JOIN mcp_connection c ON c.id = r.connection_id
                 WHERE r.issue_id = :issue AND r.organization_id = :org ORDER BY r.created_at DESC, r.id
                """)
                .param("issue", issue.issueId()).param("org", issue.organizationId())
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id", UUID.class));
                    m.put("summary", rs.getString("summary"));
                    m.put("createdAt", rs.getObject("created_at", OffsetDateTime.class).toInstant());
                    m.put("submittedBy", rs.getString("display_name"));
                    m.put("verificationRecords", rs.getLong("verifications"));
                    return m;
                }).list();
    }

    /** The report, if it belongs to an issue the caller can browse in this organization (else not found). */
    @Transactional(readOnly = true)
    Map<String, Object> get(AgentRequest request, UUID reportId) {
        Located located = locate(request, reportId);
        Map<String, Object> result = located.report();
        result.put("verifications", jdbc.sql("""
                SELECT v.outcome, v.command, v.details, v.created_at, u.display_name
                  FROM mcp_verification v LEFT JOIN user_account u ON u.id = v.recorded_by
                 WHERE v.report_id = :id ORDER BY v.created_at, v.id
                """)
                .param("id", reportId)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("outcome", rs.getString("outcome"));
                    m.put("command", rs.getString("command"));
                    m.put("details", rs.getString("details"));
                    m.put("recordedAt", rs.getObject("created_at", OffsetDateTime.class).toInstant());
                    m.put("recordedBy", rs.getString("display_name"));
                    m.put("source", "reported (not verified by JTRAC)");
                    return m;
                }).list());
        result.put("provenance", PROVENANCE);
        return result;
    }

    @Transactional
    UUID recordVerification(AgentRequest request, UUID reportId, String outcome, String command, String details) {
        Located located = locate(request, reportId);
        located.issue().project().require(ProjectPermission.COMMENT);
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO mcp_verification (id, report_id, outcome, command, details, recorded_by, connection_id)
                VALUES (:id, :report, :outcome, :command, :details, :user, :conn)
                """)
                .param("id", id).param("report", reportId).param("outcome", outcome).param("command", command)
                .param("details", details).param("user", request.user().id()).param("conn", request.connectionId()).update();
        audit.record(AuditEntry.of(request.org().organizationId(), request.user().id(), "mcp.verification.recorded")
                .target("issue", located.issue().issueId()).detail("reportId", reportId).detail("outcome", outcome)
                .detail("connectionId", request.connectionId()));
        return id;
    }

    private record Located(IssueHandle issue, Map<String, Object> report) {
    }

    private Located locate(AgentRequest request, UUID reportId) {
        record Row(String issueKey, Map<String, Object> report) {
        }
        Row row = jdbc.sql("""
                SELECT r.id, r.summary, r.root_cause, r.details::text AS details, r.created_at, r.comment_id,
                       u.display_name, p.key || '-' || i.number AS issue_key
                  FROM mcp_fix_report r JOIN issue i ON i.id = r.issue_id AND i.organization_id = r.organization_id
                  JOIN project p ON p.id = i.project_id LEFT JOIN user_account u ON u.id = r.author_id
                 WHERE r.id = :id AND r.organization_id = :org
                """)
                .param("id", reportId).param("org", request.org().organizationId())
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id", UUID.class));
                    m.put("issueKey", rs.getString("issue_key"));
                    m.put("summary", rs.getString("summary"));
                    m.put("rootCause", rs.getString("root_cause"));
                    m.putAll(json.readValue(rs.getString("details"), MAP));
                    m.put("createdAt", rs.getObject("created_at", OffsetDateTime.class).toInstant());
                    m.put("submittedBy", rs.getString("display_name"));
                    m.put("commentId", rs.getObject("comment_id", UUID.class));
                    return new Row(rs.getString("issue_key"), m);
                }).optional().orElseThrow(() -> new Args.Invalid("No fix report with that id in this organization."));
        // Proves the caller can still browse the report's issue (otherwise "not found").
        return new Located(issues.require(request.org(), row.issueKey()), row.report());
    }

    private static String commentText(NewReport report, String client) {
        StringBuilder text = new StringBuilder("**Fix report** (submitted by ")
                .append(client == null ? "an AI agent" : client.replace("*", "").replace("_", " "))
                .append(" through an agent connection; not verified by JTRAC)\n\n")
                .append(report.summary()).append('\n');
        if (report.rootCause() != null && !report.rootCause().isBlank()) {
            text.append("\n**Root cause (reported):** ").append(ToolSupport.clip(report.rootCause(), 4000)).append('\n');
        }
        if (report.details().get("testResults") instanceof List<?> results && !results.isEmpty()) {
            text.append("\n**Test results (reported):**\n");
            for (Object r : results) {
                if (r instanceof Map<?, ?> m) {
                    text.append("- ").append(m.get("outcome")).append(": `")
                            .append(String.valueOf(m.get("command")).replace("`", "'")).append("`\n");
                }
            }
        }
        if (report.details().get("changedFiles") instanceof List<?> files && !files.isEmpty()) {
            text.append("\n**Changed files (reported):** ").append(files.stream().limit(30)
                    .map(f -> f instanceof Map<?, ?> m ? "`" + String.valueOf(m.get("path")).replace("`", "'") + "`" : "")
                    .collect(Collectors.joining(", "))).append('\n');
        }
        return ToolSupport.clip(text.toString(), 45_000);
    }
}
