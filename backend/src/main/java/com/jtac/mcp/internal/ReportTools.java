package com.jtac.mcp.internal;

import static com.jtac.mcp.internal.ToolDef.Kind.ADDITIVE;
import static com.jtac.mcp.internal.ToolDef.Kind.READ;

import com.jtac.issue.IssueHandle;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Fix reports and verification records (scopes {@code reports:read} and {@code reports:write}). */
@Component
class ReportTools implements ToolDef.Provider {

    private static final List<String> OUTCOMES = List.of("PASSED", "FAILED", "PARTIAL", "NOT_RUN");
    private static final Pattern COMMIT = Pattern.compile("[0-9a-fA-F]{7,64}");

    private final ToolSupport support;
    private final FixReports reports;

    ReportTools(ToolSupport support, FixReports reports) {
        this.support = support;
        this.reports = reports;
    }

    @Override
    public List<ToolDef> tools() {
        Map<String, Object> testResult = Map.of("type", "object", "additionalProperties", false, "required", List.of("command", "outcome"),
                "properties", Map.of(
                        "command", Map.of("type", "string", "maxLength", 2000, "description", "The command you ran"),
                        "outcome", Map.of("type", "string", "enum", OUTCOMES, "description", "What you observed"),
                        "details", Map.of("type", "string", "maxLength", 4000, "description", "Counts or relevant output")));
        Map<String, Object> changedFile = Map.of("type", "object", "additionalProperties", false, "required", List.of("path"),
                "properties", Map.of("path", Map.of("type", "string", "maxLength", 500),
                        "change", Map.of("type", "string", "maxLength", 1000, "description", "What changed and why")));
        return List.of(
                new ToolDef("create_fix_report", "Create fix report",
                        "Records a fix report on an issue: summary, root cause, changed files, the test commands you ran"
                                + " and their actual outcomes, regression tests added, limitations and references. Report"
                                + " only what you actually did and observed; leave out what you did not run. JTRAC stores"
                                + " it as reported and never marks it verified. postComment also posts a summary comment"
                                + " (needs the issues:comment permission). Pass requestId to make retries safe.",
                        Scope.REPORTS_WRITE, ADDITIVE, ToolDef.Schema.object()
                                .str("issueKey", "Issue key", 32, true)
                                .str("summary", "One-paragraph summary of the fix", 500, true)
                                .str("rootCause", "The root cause you identified, with the evidence for it", 20_000, false)
                                .raw("changedFiles", Map.of("type", "array", "maxItems", 200, "items", changedFile), false)
                                .strings("testCommands", "Commands you ran", 50, 2000)
                                .raw("testResults", Map.of("type", "array", "maxItems", 50, "items", testResult), false)
                                .strings("regressionTestsAdded", "Names or paths of regression tests you added", 50, 500)
                                .str("limitations", "What is not fixed or not verified", 5000, false)
                                .str("reviewNotes", "Notes for reviewers", 5000, false)
                                .strings("commitIds", "Commit hashes (7 to 64 hex characters) you created", 20, 64)
                                .str("pullRequestUrl", "Link to the pull request you opened (https)", 500, false)
                                .bool("postComment", "Also post a summary comment on the issue")
                                .str("requestId", "Unique id for this report; retries with the same id do not duplicate it", 100, false)
                                .build(), this::create),
                new ToolDef("record_verification_result", "Record verification result",
                        "Adds a verification record (a test or check you ran and its actual outcome) to a fix report.",
                        Scope.REPORTS_WRITE, ADDITIVE, ToolDef.Schema.object()
                                .str("reportId", "Fix report id", 40, true)
                                .strEnum("outcome", "What you observed", OUTCOMES, true)
                                .str("command", "Command or check performed", 2000, false)
                                .str("details", "Relevant output or counts", 20_000, false).build(), this::verify),
                new ToolDef("list_fix_reports", "List fix reports",
                        "Fix reports recorded on an issue (newest first).",
                        Scope.REPORTS_READ, READ, ToolDef.Schema.object()
                                .str("issueKey", "Issue key", 32, true).build(), this::list),
                new ToolDef("get_fix_report", "Get fix report",
                        "A fix report with its verification history. All contents are as reported by agents or people.",
                        Scope.REPORTS_READ, READ, ToolDef.Schema.object()
                                .str("reportId", "Fix report id", 40, true).build(),
                        (request, args) -> reports.get(request, args.uuid("reportId"))));
    }

    private Object create(AgentRequest request, Args args) {
        IssueHandle issue = support.issue(request, args.issueKey("issueKey"));
        boolean postComment = args.bool("postComment", false);
        if (postComment && !request.has(Scope.ISSUES_COMMENT)) {
            throw new Args.Invalid("postComment needs the issues:comment permission on this connection.");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        List<Map<String, Object>> files = new ArrayList<>();
        for (Map<String, Object> f : args.objects("changedFiles")) {
            Args file = new Args(f);
            files.add(map("path", file.required("path"), "change", file.str("change")));
        }
        details.put("changedFiles", files);
        details.put("testCommands", args.strings("testCommands"));
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> r : args.objects("testResults")) {
            Args result = new Args(r);
            String outcome = result.required("outcome");
            if (!OUTCOMES.contains(outcome)) {
                throw new Args.Invalid("testResults.outcome must be one of " + OUTCOMES + ".");
            }
            results.add(map("command", result.required("command"), "outcome", outcome, "details", result.str("details")));
        }
        details.put("testResults", results);
        details.put("regressionTestsAdded", args.strings("regressionTestsAdded"));
        details.put("limitations", args.str("limitations"));
        details.put("reviewNotes", args.str("reviewNotes"));
        List<String> commits = args.strings("commitIds");
        commits.forEach(c -> {
            if (!COMMIT.matcher(c).matches()) {
                throw new Args.Invalid("commitIds must be commit hashes (7 to 64 hexadecimal characters).");
            }
        });
        details.put("commitIds", commits);
        String pr = args.str("pullRequestUrl");
        if (pr != null && !pr.isBlank()) {
            URI uri;
            try {
                uri = URI.create(pr.strip());
            } catch (IllegalArgumentException e) {
                throw new Args.Invalid("pullRequestUrl must be an https URL.");
            }
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new Args.Invalid("pullRequestUrl must be an https URL.");
            }
            details.put("pullRequestUrl", uri.toString()); // stored as reported; JTRAC never fetches it
        }
        String summary = args.required("summary");
        FixReports.Created created = reports.create(request, issue,
                new FixReports.NewReport(summary, args.str("rootCause"), details), postComment, args.str("requestId"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportId", created.reportId());
        result.put("issueKey", issue.key());
        result.put("duplicateOfEarlierRequest", created.duplicate());
        result.put("commentId", created.commentId());
        result.put("status", "Recorded as reported. JTRAC has not verified it.");
        return result;
    }

    private Object verify(AgentRequest request, Args args) {
        String outcome = args.required("outcome");
        if (!OUTCOMES.contains(outcome)) {
            throw new Args.Invalid("outcome must be one of " + OUTCOMES + ".");
        }
        UUID id = reports.recordVerification(request, args.uuid("reportId"), outcome, args.str("command"), args.str("details"));
        return ToolSupport.map("verificationId", id, "status", "Recorded as reported. JTRAC has not verified it.");
    }

    private Object list(AgentRequest request, Args args) {
        IssueHandle issue = support.issue(request, args.issueKey("issueKey"));
        return Map.of("issueKey", issue.key(), "reports", reports.list(issue), "provenance", FixReports.PROVENANCE);
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], pairs[i + 1]);
        }
        return m;
    }
}
