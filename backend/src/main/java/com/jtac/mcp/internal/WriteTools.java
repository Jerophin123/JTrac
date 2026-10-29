package com.jtac.mcp.internal;

import static com.jtac.mcp.internal.ToolDef.Kind.ADDITIVE;
import static com.jtac.mcp.internal.ToolDef.Kind.CHANGE;

import com.jtac.audit.AuditEntry;
import com.jtac.audit.AuditLog;
import com.jtac.collaboration.IssueComments;
import com.jtac.field.FieldCatalog;
import com.jtac.field.FieldDefinition;
import com.jtac.issue.IssueEditing;
import com.jtac.issue.IssueDetails;
import com.jtac.issue.IssueHandle;
import com.jtac.issue.IssueView;
import com.jtac.issue.Priority;
import com.jtac.project.ProjectCatalog;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Issue writes (scopes {@code issues:comment}, {@code issues:write}, {@code issues:transition}).
 * Everything goes through the issue and collaboration modules, so the user's project
 * permissions, the workflow's transitions for their roles, field rules and optimistic
 * locking apply exactly as in the web app. No tool deletes issues or comments.
 */
@Component
class WriteTools implements ToolDef.Provider {

    private static final List<String> PRIORITIES = Arrays.stream(Priority.values()).map(Enum::name).toList();

    private final ToolSupport support;
    private final IssueDetails details;
    private final IssueEditing editing;
    private final IssueComments comments;
    private final ProjectCatalog catalog;
    private final FieldCatalog fields;
    private final Idempotency idempotency;
    private final AuditLog audit;
    private final TransactionTemplate transactions;

    WriteTools(ToolSupport support, IssueDetails details, IssueEditing editing, IssueComments comments, ProjectCatalog catalog,
            FieldCatalog fields, Idempotency idempotency, AuditLog audit, TransactionTemplate transactions) {
        this.support = support;
        this.details = details;
        this.editing = editing;
        this.comments = comments;
        this.catalog = catalog;
        this.fields = fields;
        this.idempotency = idempotency;
        this.audit = audit;
        this.transactions = transactions;
    }

    private static ToolDef.Schema fieldChanges(ToolDef.Schema schema) {
        return schema
                .str("title", "New title", 255, false)
                .nullableStr("description", "New description (Markdown); null clears it", 100_000)
                .strEnum("priority", "New priority", PRIORITIES, false)
                .nullableStr("assignee", "'me', a user handle, or null to unassign", 40)
                .raw("labels", Map.of("type", "array", "maxItems", 20, "description",
                        "Replaces the labels with these label names (must exist in the project)",
                        "items", Map.of("type", "string", "maxLength", 60)), false)
                .nullableStr("dueDate", "YYYY-MM-DD, or null to clear", 10)
                .nullableStr("startDate", "YYYY-MM-DD, or null to clear", 10)
                .nullableStr("milestone", "Milestone name, or null to clear", 120)
                .raw("customFields", Map.of("type", "object", "description",
                        "Custom field values by field key (from get_project); option fields take option labels; null clears",
                        "maxProperties", 30), false);
    }

    @Override
    public List<ToolDef> tools() {
        return List.of(
                new ToolDef("add_issue_comment", "Add issue comment",
                        "Adds a comment to an issue as the user (Markdown; @handle mentions notify people who can see the"
                                + " issue). Pass requestId so that a retry does not post the comment twice.",
                        Scope.ISSUES_COMMENT, ADDITIVE, ToolDef.Schema.object()
                                .str("issueKey", "Issue key", 32, true)
                                .str("body", "Comment text (Markdown)", 50_000, true)
                                .str("requestId", "Unique id for this comment; retries with the same id return the first comment", 100, false)
                                .build(), this::comment),
                new ToolDef("update_issue", "Update issue fields",
                        "Changes issue fields. Requires the issue's current version from get_issue (fails if someone"
                                + " changed it since). JTRAC enforces your permissions and field rules (read-only fields"
                                + " cannot change; required fields cannot be cleared). Use transition_issue for status.",
                        Scope.ISSUES_WRITE, CHANGE, fieldChanges(ToolDef.Schema.object()
                                .str("issueKey", "Issue key", 32, true)
                                .integer("version", "The issue version from get_issue", 0, Integer.MAX_VALUE))
                                .build(), this::update),
                new ToolDef("transition_issue", "Transition issue",
                        "Moves an issue through a workflow transition (by transition id or name, or the target status"
                                + " name) that the workflow allows for your roles. Fields required in the target status can"
                                + " be set in the same call. Requires the issue's current version. See"
                                + " get_allowed_transitions.",
                        Scope.ISSUES_TRANSITION, CHANGE, fieldChanges(ToolDef.Schema.object()
                                .str("issueKey", "Issue key", 32, true)
                                .integer("version", "The issue version from get_issue", 0, Integer.MAX_VALUE)
                                .str("transition", "Transition id or name, or the target status name", 120, true))
                                .build(), this::transition),
                new ToolDef("link_issues", "Link issues",
                        "Links two issues you can see: BLOCKS (this issue blocks the target), RELATES or DUPLICATES (this"
                                + " issue duplicates the target).",
                        Scope.ISSUES_WRITE, ADDITIVE, ToolDef.Schema.object()
                                .str("issueKey", "Issue key", 32, true)
                                .str("targetKey", "The other issue's key", 32, true)
                                .strEnum("type", "Link type", List.of("BLOCKS", "RELATES", "DUPLICATES"), true).build(), this::link),
                new ToolDef("unlink_issues", "Remove issue link",
                        "Removes a link (id from get_issue links).",
                        Scope.ISSUES_WRITE, CHANGE, ToolDef.Schema.object()
                                .str("issueKey", "Issue key", 32, true)
                                .str("linkId", "Link id from get_issue", 40, true).build(), this::unlink));
    }

    private Object comment(AgentRequest request, Args args) {
        IssueHandle issue = support.issue(request, args.issueKey("issueKey"));
        String body = args.required("body");
        String requestId = args.str("requestId");
        return transactions.execute(status -> {
            Optional<String> previous = idempotency.previous(request.connectionId(), requestId, "add_issue_comment");
            if (previous.isPresent()) {
                return ToolSupport.map("commentId", previous.get(), "issueKey", issue.key(), "duplicateOfEarlierRequest", true);
            }
            IssueComments.Comment comment = comments.add(issue, body);
            idempotency.remember(request.connectionId(), requestId, "add_issue_comment", comment.id().toString());
            audited(request, issue.issueId(), issue.key(), "mcp.issue.commented");
            return ToolSupport.map("commentId", comment.id(), "issueKey", issue.key(), "url", support.link(request, issue.key()),
                    "duplicateOfEarlierRequest", false);
        });
    }

    private Object update(AgentRequest request, Args args) {
        String key = args.issueKey("issueKey");
        long version = version(args);
        IssueView current = details.view(request.org(), key);
        Map<String, Object> patch = changes(request, current, args);
        if (patch.isEmpty()) {
            throw new Args.Invalid("Give at least one field to change.");
        }
        IssueView saved = editing.update(request.org(), key, patch, version);
        audited(request, saved.id(), key, "mcp.issue.updated", String.join(",", patch.keySet()));
        return result(request, saved);
    }

    private Object transition(AgentRequest request, Args args) {
        String key = args.issueKey("issueKey");
        long version = version(args);
        IssueView current = details.view(request.org(), key);
        String wanted = args.required("transition");
        List<IssueView.TransitionView> matches = current.transitions().stream()
                .filter(t -> t.id().equals(wanted) || t.name().equalsIgnoreCase(wanted) || t.to().name().equalsIgnoreCase(wanted))
                .toList();
        if (matches.isEmpty()) {
            throw new Args.Invalid("No transition '" + wanted + "' is available to you from " + current.status().name()
                    + ". Available: " + current.transitions().stream().map(t -> t.name() + " → " + t.to().name()).toList());
        }
        if (matches.stream().map(t -> t.to().id()).distinct().count() > 1) {
            throw new Args.Invalid("'" + wanted + "' is ambiguous; use a transition id: "
                    + matches.stream().map(t -> t.id() + " (" + t.name() + ")").toList());
        }
        Map<String, Object> patch = changes(request, current, args);
        patch.put("statusId", matches.getFirst().to().id().toString());
        IssueView saved = editing.update(request.org(), key, patch, version);
        audited(request, saved.id(), key, "mcp.issue.transitioned", current.status().name() + " -> " + saved.status().name());
        return result(request, saved);
    }

    private Object link(AgentRequest request, Args args) {
        String key = args.issueKey("issueKey");
        IssueView saved = editing.link(request.org(), key, args.issueKey("targetKey"), args.required("type"));
        audited(request, saved.id(), key, "mcp.issue.linked");
        return result(request, saved);
    }

    private Object unlink(AgentRequest request, Args args) {
        String key = args.issueKey("issueKey");
        UUID linkId = args.uuid("linkId");
        editing.unlink(request.org(), key, linkId);
        IssueView saved = details.view(request.org(), key);
        audited(request, saved.id(), key, "mcp.issue.unlinked");
        return result(request, saved);
    }

    // --- helpers -----------------------------------------------------------------------------

    private static long version(Args args) {
        Long version = args.longValue("version");
        if (version == null) {
            throw new Args.Invalid("version is required: read it with get_issue.");
        }
        return version;
    }

    private Map<String, Object> result(AgentRequest request, IssueView saved) {
        Map<String, Object> result = support.detail(request, saved, 2000);
        result.put("note", "Saved. This is the issue's state after the change.");
        return result;
    }

    /** Translates agent-friendly values (handles, names, labels) to the issue PATCH format. */
    private Map<String, Object> changes(AgentRequest request, IssueView current, Args args) {
        UUID projectId = current.project().id();
        Map<String, Object> patch = new HashMap<>();
        if (args.has("title")) {
            patch.put("title", args.required("title"));
        }
        if (args.has("description")) {
            patch.put("description", args.str("description"));
        }
        if (args.has("priority")) {
            patch.put("priority", Priority.valueOf(args.required("priority")).name());
        }
        if (args.has("assignee")) {
            String assignee = args.str("assignee");
            patch.put("assigneeId", assignee == null || assignee.isBlank() || assignee.equalsIgnoreCase("none") ? null
                    : assignee.equalsIgnoreCase("me") ? request.user().id().toString()
                    : support.memberId(request, assignee.strip()).toString());
        }
        if (args.has("labels")) {
            List<ProjectCatalog.Label> labels = catalog.labels(projectId);
            List<String> ids = new ArrayList<>();
            for (String name : args.strings("labels")) {
                ids.add(labels.stream().filter(l -> l.name().equalsIgnoreCase(name)).findFirst()
                        .orElseThrow(() -> new Args.Invalid("Unknown label in this project: " + name)).id().toString());
            }
            patch.put("labelIds", ids);
        }
        for (String date : List.of("dueDate", "startDate")) {
            if (args.has(date)) {
                patch.put(date, args.date(date) == null ? null : args.date(date).toString());
            }
        }
        if (args.has("milestone")) {
            String name = args.str("milestone");
            patch.put("milestoneId", name == null || name.isBlank() ? null : catalog.milestones(projectId).stream()
                    .filter(m -> m.name().equalsIgnoreCase(name.strip())).findFirst()
                    .orElseThrow(() -> new Args.Invalid("Unknown milestone in this project: " + name)).id().toString());
        }
        if (args.has("customFields")) {
            Map<String, FieldDefinition> defs = new LinkedHashMap<>();
            fields.fields(projectId).forEach(d -> defs.put(d.key(), d));
            Map<String, Object> custom = new HashMap<>();
            args.map("customFields").forEach((fieldKey, value) -> {
                FieldDefinition def = defs.get(fieldKey);
                if (def == null) {
                    throw new Args.Invalid("Unknown custom field in this project: " + fieldKey);
                }
                custom.put(fieldKey, optionIds(def, value));
            });
            patch.put("customValues", custom);
        }
        return patch;
    }

    /** Option fields accept option labels (or ids); other values pass through to field validation. */
    private static Object optionIds(FieldDefinition def, Object value) {
        if (value == null || def.options() == null || def.options().isEmpty()) {
            return value;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(v -> optionId(def, v)).toList();
        }
        return optionId(def, value);
    }

    private static String optionId(FieldDefinition def, Object value) {
        String text = String.valueOf(value);
        return def.options().stream().filter(o -> o.id().equals(text) || o.label().equalsIgnoreCase(text)).findFirst()
                .map(FieldDefinition.Option::id)
                .orElseThrow(() -> new Args.Invalid("'" + text + "' is not an option of " + def.name().toLowerCase(Locale.ROOT) + "."));
    }

    private void audited(AgentRequest request, UUID issueId, String key, String action) {
        audited(request, issueId, key, action, null);
    }

    private void audited(AgentRequest request, UUID issueId, String key, String action, String change) {
        audit.record(AuditEntry.of(request.org().organizationId(), request.user().id(), action).target("issue", issueId)
                .detail("issueKey", key).detail("change", change).detail("connectionId", request.connectionId())
                .detail("agent", request.clientName()));
    }
}
