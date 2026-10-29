package com.jtac.mcp.internal;

import static com.jtac.mcp.internal.ToolDef.Kind.READ;

import com.jtac.attachment.IssueAttachments;
import com.jtac.collaboration.IssueComments;
import com.jtac.issue.IssueActivityEntry;
import com.jtac.issue.IssueDetails;
import com.jtac.issue.IssueFilter;
import com.jtac.issue.IssueHandle;
import com.jtac.issue.IssueSearch;
import com.jtac.issue.IssueSummary;
import com.jtac.issue.IssueView;
import com.jtac.project.ProjectCatalog;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Task preparation for coding agents (scope {@code issues:read}). Builds a structured brief
 * from JTRAC records only. It separates facts read from JTRAC, text extracted from what
 * people wrote, and what nobody has established yet; it never claims to know the cause.
 */
@Component
class PrepTools implements ToolDef.Provider {

    private final ToolSupport support;
    private final IssueDetails details;
    private final IssueComments comments;
    private final IssueAttachments attachments;
    private final IssueSearch search;
    private final ProjectCatalog catalog;
    private final McpProperties properties;

    PrepTools(ToolSupport support, IssueDetails details, IssueComments comments, IssueAttachments attachments,
            IssueSearch search, ProjectCatalog catalog, McpProperties properties) {
        this.support = support;
        this.details = details;
        this.comments = comments;
        this.attachments = attachments;
        this.search = search;
        this.catalog = catalog;
        this.properties = properties;
    }

    @Override
    public List<ToolDef> tools() {
        return List.of(new ToolDef("prepare_fix_context", "Prepare fix context",
                "A structured brief for working on an issue: its facts, description sections found by heading"
                        + " (reproduction steps, expected and actual behaviour, environment, acceptance criteria), error"
                        + " evidence (exception types and error lines in the description, comments and text attachments),"
                        + " recent comments, status and assignment history, attachments, linked and similar issues, project"
                        + " context, open questions and suggested verification steps. Start here before changing code.",
                Scope.ISSUES_READ, READ, ToolDef.Schema.object()
                        .str("issueKey", "Issue key", 32, true)
                        .integer("maxComments", "Most recent comments to include (default 10)", 0, 50)
                        .build(), this::prepare));
    }

    Map<String, Object> prepare(AgentRequest request, Args args) {
        String key = args.issueKey("issueKey");
        int maxComments = args.integer("maxComments", 10, 0, 50);
        IssueView view = details.view(request.org(), key);
        IssueHandle handle = support.issue(request, key);
        Map<String, Object> brief = new LinkedHashMap<>();
        brief.put("generatedAt", Instant.now());
        brief.put("issue", support.detail(request, view, 20_000));

        Map<String, String> sections = Evidence.sections(view.description());
        Map<String, Object> extracted = new LinkedHashMap<>();
        for (String section : List.of("reproductionSteps", "expectedBehavior", "actualBehavior", "environment", "acceptanceCriteria")) {
            extracted.put(section, sections.get(section));
        }
        extracted.put("method", "Sections found by matching headings in the description. null means no such heading; the"
                + " information may still be elsewhere in the description or comments.");
        brief.put("descriptionSections", extracted);

        List<IssueComments.Comment> all = comments.list(handle);
        List<Map<String, Object>> recent = new ArrayList<>();
        for (IssueComments.Comment c : all.subList(Math.max(0, all.size() - maxComments), all.size())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("author", c.authorName());
            m.put("at", c.createdAt());
            m.put("body", ToolSupport.clip(c.body(), 4000));
            recent.add(m);
        }
        brief.put("recentComments", ToolSupport.map("total", all.size(), "included", recent));

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("description", Evidence.errorSignals(view.description() == null ? "" : view.description(), 30));
        StringBuilder commentText = new StringBuilder();
        all.forEach(c -> commentText.append(c.body()).append('\n'));
        evidence.put("comments", Evidence.errorSignals(commentText.toString(), 30));
        List<Map<String, Object>> files = new ArrayList<>();
        if (request.has(Scope.ATTACHMENTS_READ)) {
            List<IssueAttachments.Attachment> list = attachments.list(handle);
            List<Map<String, Object>> fromFiles = new ArrayList<>();
            int scanned = 0;
            for (IssueAttachments.Attachment a : list) {
                files.add(AttachmentTools.metadata(a));
                if (AttachmentTools.IMAGE_TYPES.contains(a.contentType()) || scanned >= 5) {
                    continue;
                }
                scanned++;
                IssueAttachments.Content content = attachments.read(handle, a.id(), (int) properties.maxAttachmentRead().toBytes());
                Optional<String> text = Evidence.decodeText(content.bytes(), content.truncated());
                text.ifPresent(t -> {
                    Map<String, Object> signals = new LinkedHashMap<>(Evidence.errorSignals(t, 15));
                    signals.put("fileName", a.fileName());
                    signals.put("attachmentId", a.id());
                    fromFiles.add(signals);
                });
            }
            evidence.put("attachments", fromFiles);
            brief.put("attachments", files);
        } else {
            brief.put("attachments", "Not included: this connection does not have the attachments:read permission.");
        }
        evidence.put("method", "Exception names, error-level log lines and stack frames found by pattern matching.");
        brief.put("errorEvidence", evidence);

        List<IssueActivityEntry> history = details.activity(request.org(), key);
        brief.put("statusHistory", changes(history, "status"));
        brief.put("assignmentHistory", changes(history, "assignee"));

        List<Map<String, Object>> related = new ArrayList<>();
        view.links().forEach(l -> related.add(ToolSupport.map("key", l.key(), "relation", l.type() + " (" + l.direction() + ")",
                "title", l.title(), "status", ToolSupport.status(l.status()))));
        if (view.parent() != null) {
            related.add(ToolSupport.map("key", view.parent().key(), "relation", "parent", "title", view.parent().title()));
        }
        view.subtasks().forEach(s -> related.add(ToolSupport.map("key", s.key(), "relation", "sub-task", "title", s.title(),
                "status", ToolSupport.status(s.status()))));
        brief.put("relatedIssues", related);
        brief.put("similarIssues", similar(request, view));

        Map<String, Object> project = new LinkedHashMap<>();
        project.put("key", view.project().name());
        if (request.has(Scope.PROJECTS_READ)) {
            project.put("description", ToolSupport.clip(catalog.description(view.project().id()).orElse(null), 3000));
        }
        brief.put("project", project);

        brief.put("notDeterminedByJtrac", List.of(
                "The root cause: nothing in this brief has been verified against code.",
                "Which files or components are affected: JTRAC has no access to your repository.",
                "Whether the problem still reproduces on the current code."));
        brief.put("suggestedVerification", suggestedSteps(extracted));
        brief.put("provenance", "Issue fields, history, links and comments are read from JTRAC with your permissions."
                + " Section extraction, error evidence and similar issues are heuristics over that text.");
        brief.put("contentNotice", ToolSupport.UNTRUSTED);
        return brief;
    }

    private static List<Map<String, Object>> changes(List<IssueActivityEntry> history, String field) {
        return history.stream().filter(e -> field.equals(e.field())).map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", e.at());
            m.put("from", e.oldValue());
            m.put("to", e.newValue());
            m.put("by", e.actor() == null ? null : e.actor().displayName());
            return m;
        }).toList();
    }

    private List<Map<String, Object>> similar(AgentRequest request, IssueView view) {
        List<String> keywords = IssueTools.similarityKeywords(view.title(), view.description());
        if (keywords.isEmpty()) {
            return List.of();
        }
        IssueFilter filter = new IssueFilter(null, String.join(" or ", keywords), null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, "updated", "desc");
        List<Map<String, Object>> result = new ArrayList<>();
        for (IssueSummary s : search.search(request.org(), filter, 0, 6).items()) {
            if (!s.key().equals(view.key()) && result.size() < 5) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", s.key());
                m.put("title", s.title());
                m.put("status", ToolSupport.status(s.status()));
                m.put("resolvedAt", s.resolvedAt());
                result.add(m);
            }
        }
        return result;
    }

    private static List<String> suggestedSteps(Map<String, Object> sections) {
        List<String> steps = new ArrayList<>();
        steps.add(sections.get("reproductionSteps") != null
                ? "Reproduce the problem with the reproduction steps above before changing code."
                : "No reproduction steps were found; derive them from the description and comments, or ask the reporter.");
        steps.add("Write a failing automated test that captures the expected behaviour, then make it pass.");
        if (sections.get("acceptanceCriteria") != null) {
            steps.add("Check every acceptance criterion listed above.");
        }
        steps.add("Run the project's existing test suite and note the actual results.");
        steps.add("Record what you changed and the actual test outcomes with create_fix_report (if enabled), or tell the user.");
        return steps;
    }
}
