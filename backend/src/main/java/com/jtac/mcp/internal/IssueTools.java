package com.jtac.mcp.internal;

import static com.jtac.mcp.internal.ToolDef.Kind.READ;

import com.jtac.collaboration.IssueComments;
import com.jtac.field.FieldCatalog;
import com.jtac.field.FieldDefinition;
import com.jtac.issue.IssueActivityEntry;
import com.jtac.issue.IssueDetails;
import com.jtac.issue.IssueFilter;
import com.jtac.issue.IssueHandle;
import com.jtac.issue.IssueSearch;
import com.jtac.issue.IssueSummary;
import com.jtac.issue.IssueView;
import com.jtac.issue.Priority;
import com.jtac.project.ProjectAccess;
import com.jtac.project.ProjectCatalog;
import com.jtac.project.ProjectContext;
import com.jtac.search.SavedViews;
import com.jtac.shared.web.PageResponse;
import com.jtac.workflow.FieldAccess;
import com.jtac.workflow.StatusCategory;
import com.jtac.workflow.Workflow;
import com.jtac.workflow.WorkflowCatalog;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Issue discovery, details, comments, history, workflow options and saved filters (scope {@code issues:read}). */
@Component
class IssueTools implements ToolDef.Provider {

    private static final List<String> CATEGORIES = Arrays.stream(StatusCategory.values()).map(Enum::name).toList();
    private static final List<String> PRIORITIES = Arrays.stream(Priority.values()).map(Enum::name).toList();
    private static final List<String> SORTS = List.of("updated", "created", "priority", "dueDate", "key", "title", "status");
    private static final Set<String> STOP_WORDS = Set.of("when", "with", "from", "that", "this", "there", "into", "after",
            "before", "does", "doesn", "should", "would", "could", "fails", "failed", "error", "issue", "problem", "page",
            "about", "have", "your", "some", "while", "being", "only");

    private final ToolSupport support;
    private final IssueSearch search;
    private final IssueDetails details;
    private final IssueComments comments;
    private final ProjectAccess projects;
    private final ProjectCatalog catalog;
    private final WorkflowCatalog workflows;
    private final FieldCatalog fields;
    private final SavedViews views;

    IssueTools(ToolSupport support, IssueSearch search, IssueDetails details, IssueComments comments, ProjectAccess projects,
            ProjectCatalog catalog, WorkflowCatalog workflows, FieldCatalog fields, SavedViews views) {
        this.support = support;
        this.search = search;
        this.details = details;
        this.comments = comments;
        this.projects = projects;
        this.catalog = catalog;
        this.workflows = workflows;
        this.fields = fields;
        this.views = views;
    }

    @Override
    public List<ToolDef> tools() {
        return List.of(
                new ToolDef("get_current_user", "Current user and connection",
                        "Who this connection acts as: the JTRAC user, organization and role, and the permissions enabled"
                                + " for this connection. Call this first if unsure what you may do.",
                        null, READ, ToolDef.Schema.object().build(), this::currentUser),
                new ToolDef("search_issues", "Search issues",
                        "Search issues you can see. Combine filters: full-text query (title/description; also matches an"
                                + " issue key), projects, status categories, assignee ('me', 'none' or a handle), reporter"
                                + " ('me' or a handle), priorities, labels, resolved, updatedSince. Use assignee='me' and"
                                + " resolved=false for your open work, sort='updated' for recent activity, or an error"
                                + " message as the query to find issues mentioning it. Paginated (pageSize up to 50).",
                        Scope.ISSUES_READ, READ, ToolDef.Schema.object()
                                .str("query", "Full-text query; supports quoted phrases, OR and -exclusion", 300, false)
                                .strings("projectKeys", "Only these projects (keys such as WEB)", 20, 20)
                                .enums("statusCategories", "Only these status categories", CATEGORIES)
                                .str("assignee", "'me', 'none', or a user handle", 40, false)
                                .str("reporter", "'me' or a user handle", 40, false)
                                .enums("priorities", "Only these priorities", PRIORITIES)
                                .strings("labels", "Label names (any of them)", 20, 60)
                                .bool("resolved", "true: only resolved (done/canceled); false: only open")
                                .str("updatedSince", "Only issues updated since this date or timestamp", 40, false)
                                .strEnum("sort", "Sort field (default updated)", SORTS, false)
                                .strEnum("direction", "asc or desc (default desc)", List.of("asc", "desc"), false)
                                .integer("page", "Zero-based page", 0, 1000)
                                .integer("pageSize", "Results per page (default 20)", 1, 50)
                                .build(), this::searchIssues),
                new ToolDef("get_issue", "Get issue",
                        "An issue with every field you may see: description, status, priority, people, labels,"
                                + " milestone, dates, custom fields, links, sub-tasks, the transitions available to you,"
                                + " fields hidden from or read-only for you, your permissions, and its version (needed for"
                                + " updates).",
                        Scope.ISSUES_READ, READ, ToolDef.Schema.object()
                                .str("issueKey", "Issue key, e.g. WEB-12", 32, true)
                                .integer("maxDescriptionChars", "Truncate the description (default 20000)", 200, 100_000)
                                .build(), this::getIssue),
                new ToolDef("get_issue_comments", "Get issue comments",
                        "Comments on an issue in posting order, optionally only those containing a text (case-insensitive).",
                        Scope.ISSUES_READ, READ, ToolDef.Schema.object()
                                .str("issueKey", "Issue key", 32, true)
                                .str("contains", "Only comments containing this text", 200, false)
                                .integer("offset", "Skip this many matching comments", 0, 10_000)
                                .integer("limit", "At most this many (default 50)", 1, 200)
                                .integer("maxBodyChars", "Truncate each comment (default 4000)", 100, 50_000)
                                .build(), this::getComments),
                new ToolDef("get_issue_history", "Get issue history",
                        "The attributed change history of an issue: field changes, status transitions, assignments,"
                                + " links, comments and attachments added. Filter by field (e.g. 'status' for the status"
                                + " history, 'assignee' for the assignment history) or kind (e.g. TRANSITIONED, COMMENTED)."
                                + " Changes to fields hidden from you are omitted.",
                        Scope.ISSUES_READ, READ, ToolDef.Schema.object()
                                .str("issueKey", "Issue key", 32, true)
                                .str("field", "Only changes to this field (status, assignee, priority, description, ...)", 60, false)
                                .str("kind", "Only entries of this kind (CREATED, UPDATED, TRANSITIONED, COMMENTED, ...)", 40, false)
                                .integer("limit", "Return at most the latest N entries (default 100)", 1, 500)
                                .build(), this::getHistory),
                new ToolDef("find_similar_issues", "Find similar issues",
                        "Issues whose title or description share keywords with the given issue (keyword overlap via"
                                + " JTRAC full-text search; not semantic search). Also lists issues already linked to it.",
                        Scope.ISSUES_READ, READ, ToolDef.Schema.object()
                                .str("issueKey", "Issue key", 32, true)
                                .integer("limit", "At most this many (default 10)", 1, 25)
                                .bool("includeResolved", "Include resolved issues (default true; useful for regressions)")
                                .build(), this::findSimilar),
                new ToolDef("get_allowed_transitions", "Get allowed transitions",
                        "The workflow transitions you may use on an issue now (from its project's published workflow and"
                                + " your roles), with the fields that are required in each target status and whether they"
                                + " are still empty. Use before transition_issue.",
                        Scope.ISSUES_READ, READ, ToolDef.Schema.object()
                                .str("issueKey", "Issue key", 32, true)
                                .build(), this::allowedTransitions),
                new ToolDef("list_saved_filters", "List saved filters",
                        "Saved issue views (filters) you can use: your own and shared ones of projects you can see.",
                        Scope.ISSUES_READ, READ, ToolDef.Schema.object()
                                .str("projectKey", "Only views of this project", 20, false)
                                .build(), this::listViews),
                new ToolDef("run_saved_filter", "Run saved filter",
                        "Runs a saved view's filter with your permissions and returns matching issues.",
                        Scope.ISSUES_READ, READ, ToolDef.Schema.object()
                                .str("filterId", "Id from list_saved_filters", 40, true)
                                .integer("page", "Zero-based page", 0, 1000)
                                .integer("pageSize", "Results per page (default 20)", 1, 50)
                                .build(), this::runView));
    }

    // --- handlers ----------------------------------------------------------------------------

    private Object currentUser(AgentRequest request, Args args) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user", ToolSupport.map("displayName", request.user().displayName(), "handle", request.user().handle(),
                "email", request.user().email()));
        result.put("organization", Map.of("slug", request.org().slug(), "name", request.org().name(),
                "role", request.org().role().name()));
        result.put("connection", ToolSupport.map("name", request.principal().connection().name(),
                "permissions", Scope.wires(request.scopes()), "agent", request.clientName()));
        result.put("note", "Permissions above only narrow what you may do; every call is also limited by this user's"
                + " project roles and field rules.");
        return result;
    }

    private Object searchIssues(AgentRequest request, Args args) {
        IssueFilter filter = filter(request, args);
        if (filter == null) {
            return Map.of("items", List.of(), "total", 0, "note", "No label with those names exists in projects you can see.");
        }
        int page = args.integer("page", 0, 0, 1000);
        int size = args.integer("pageSize", 20, 1, 50);
        return page(request, search.search(request.org(), filter, page, size));
    }

    Map<String, Object> page(AgentRequest request, PageResponse<IssueSummary> result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("items", result.items().stream().map(s -> support.summary(request, s)).toList());
        map.put("page", result.page());
        map.put("pageSize", result.size());
        map.put("total", result.total());
        map.put("hasMore", (long) (result.page() + 1) * result.size() < result.total());
        map.put("contentNotice", ToolSupport.UNTRUSTED);
        return map;
    }

    /** The filter for search_issues, or {@code null} when a label filter matches no label at all. */
    private IssueFilter filter(AgentRequest request, Args args) {
        List<String> projectKeys = args.strings("projectKeys").stream().map(k -> k.toUpperCase(Locale.ROOT)).toList();
        projectKeys.forEach(k -> {
            if (!Args.PROJECT_KEY.matcher(k).matches()) {
                throw new Args.Invalid("projectKeys must contain project keys such as WEB.");
            }
        });
        List<StatusCategory> categories = args.strings("statusCategories").stream().map(StatusCategory::valueOf).toList();
        List<Priority> priorities = args.strings("priorities").stream().map(Priority::valueOf).toList();
        List<String> assignees = null;
        String assignee = args.str("assignee");
        if (assignee != null && !assignee.isBlank()) {
            String a = assignee.strip();
            assignees = List.of(a.equalsIgnoreCase("me") ? "me" : a.equalsIgnoreCase("none") ? "none" : support.memberId(request, a).toString());
        }
        List<UUID> reporters = null;
        String reporter = args.str("reporter");
        if (reporter != null && !reporter.isBlank()) {
            reporters = List.of(reporter.strip().equalsIgnoreCase("me") ? request.user().id() : support.memberId(request, reporter.strip()));
        }
        List<UUID> labelIds = null;
        List<String> labels = args.strings("labels");
        if (!labels.isEmpty()) {
            Set<String> wanted = new HashSet<>();
            labels.forEach(l -> wanted.add(l.toLowerCase(Locale.ROOT)));
            labelIds = new ArrayList<>();
            for (ProjectContext ctx : projects.visible(request.org())) {
                if (!projectKeys.isEmpty() && !projectKeys.contains(ctx.project().key())) {
                    continue;
                }
                for (ProjectCatalog.Label label : catalog.labels(ctx.projectId())) {
                    if (wanted.contains(label.name().toLowerCase(Locale.ROOT))) {
                        labelIds.add(label.id());
                    }
                }
            }
            if (labelIds.isEmpty()) {
                return null;
            }
        }
        String sort = args.str("sort") == null ? "updated" : args.str("sort");
        String direction = args.str("direction") == null ? "desc" : args.str("direction");
        return new IssueFilter(projectKeys.isEmpty() ? null : projectKeys, args.str("query"), null,
                categories.isEmpty() ? null : categories, assignees, reporters, priorities.isEmpty() ? null : priorities,
                labelIds, null, null, null, null, args.since("updatedSince"), args.bool("resolved"), null, null, null, sort,
                direction);
    }

    private Object getIssue(AgentRequest request, Args args) {
        IssueView view = details.view(request.org(), args.issueKey("issueKey"));
        Map<String, Object> result = support.detail(request, view, args.integer("maxDescriptionChars", 20_000, 200, 100_000));
        result.put("contentNotice", ToolSupport.UNTRUSTED);
        return result;
    }

    private Object getComments(AgentRequest request, Args args) {
        IssueHandle issue = support.issue(request, args.issueKey("issueKey"));
        String contains = args.str("contains");
        String needle = contains == null || contains.isBlank() ? null : contains.toLowerCase(Locale.ROOT);
        List<IssueComments.Comment> matching = comments.list(issue).stream()
                .filter(c -> needle == null || c.body().toLowerCase(Locale.ROOT).contains(needle)).toList();
        int offset = args.integer("offset", 0, 0, 10_000);
        int limit = args.integer("limit", 50, 1, 200);
        int max = args.integer("maxBodyChars", 4000, 100, 50_000);
        List<Map<String, Object>> items = matching.stream().skip(offset).limit(limit).map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("author", c.authorName());
            m.put("authorHandle", c.authorHandle());
            m.put("createdAt", c.createdAt());
            m.put("editedAt", c.editedAt());
            m.put("body", ToolSupport.clip(c.body(), max));
            return m;
        }).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("issueKey", issue.key());
        result.put("total", matching.size());
        result.put("offset", offset);
        result.put("comments", items);
        result.put("contentNotice", ToolSupport.UNTRUSTED);
        return result;
    }

    private Object getHistory(AgentRequest request, Args args) {
        String key = args.issueKey("issueKey");
        String field = args.str("field");
        String kind = args.str("kind");
        List<IssueActivityEntry> entries = details.activity(request.org(), key).stream()
                .filter(e -> field == null || field.isBlank() || field.strip().equalsIgnoreCase(e.field()))
                .filter(e -> kind == null || kind.isBlank() || kind.strip().equalsIgnoreCase(e.kind()))
                .toList();
        int limit = args.integer("limit", 100, 1, 500);
        int skip = Math.max(0, entries.size() - limit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("issueKey", key);
        result.put("omittedEarlier", skip);
        result.put("entries", entries.stream().skip(skip).map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", e.at());
            m.put("kind", e.kind());
            m.put("field", e.field());
            m.put("from", e.oldValue());
            m.put("to", e.newValue());
            m.put("by", e.actor() == null ? null : e.actor().displayName());
            return m;
        }).toList());
        return result;
    }

    private Object findSimilar(AgentRequest request, Args args) {
        String key = args.issueKey("issueKey");
        IssueView view = details.view(request.org(), key);
        int limit = args.integer("limit", 10, 1, 25);
        List<String> keywords = similarityKeywords(view.title(), view.description());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("issueKey", key);
        result.put("keywords", keywords);
        result.put("method", "Keyword overlap using JTRAC full-text search over titles and descriptions; a heuristic, not proof"
                + " that issues share a cause.");
        result.put("linked", view.links().stream().map(l -> ToolSupport.map("key", l.key(), "type", l.type(), "title", l.title())).toList());
        if (keywords.isEmpty()) {
            result.put("similar", List.of());
            return result;
        }
        Boolean includeResolved = args.bool("includeResolved");
        IssueFilter filter = new IssueFilter(null, String.join(" or ", keywords), null, null, null, null, null, null, null, null,
                null, null, null, Boolean.FALSE.equals(includeResolved) ? Boolean.FALSE : null, null, null, null, "updated", "desc");
        List<IssueSummary> candidates = search.search(request.org(), filter, 0, 50).items();
        record Scored(IssueSummary issue, int score, List<String> shared) {
        }
        List<Scored> scored = new ArrayList<>();
        for (IssueSummary candidate : candidates) {
            if (candidate.key().equals(key)) {
                continue;
            }
            String title = candidate.title().toLowerCase(Locale.ROOT);
            List<String> shared = keywords.stream().filter(title::contains).toList();
            scored.add(new Scored(candidate, shared.size(), shared));
        }
        scored.sort((a, b) -> Integer.compare(b.score(), a.score()));
        result.put("similar", scored.stream().limit(limit).map(s -> {
            Map<String, Object> m = support.summary(request, s.issue());
            m.put("sharedTitleKeywords", s.shared());
            return m;
        }).toList());
        result.put("contentNotice", ToolSupport.UNTRUSTED);
        return result;
    }

    /** Title keywords first (they name the problem), then description keywords, at most eight. */
    static List<String> similarityKeywords(String title, String description) {
        Set<String> words = new LinkedHashSet<>(keywords(title));
        if (description != null) {
            words.addAll(keywords(ToolSupport.clip(description, 2000)));
        }
        return words.stream().limit(8).toList();
    }

    /** Distinctive words of a text (longest first), for keyword search. */
    static List<String> keywords(String text) {
        Set<String> words = new LinkedHashSet<>();
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_]+")) {
            if (word.length() >= 4 && word.length() <= 40 && !STOP_WORDS.contains(word) && !word.chars().allMatch(Character::isDigit)) {
                words.add(word);
            }
        }
        return words.stream().sorted((a, b) -> Integer.compare(b.length(), a.length())).limit(6).toList();
    }

    private Object allowedTransitions(AgentRequest request, Args args) {
        String key = args.issueKey("issueKey");
        IssueView view = details.view(request.org(), key);
        ProjectContext ctx = projects.require(request.org(), view.project().id());
        Workflow workflow = workflows.published(ctx.projectId());
        List<FieldDefinition> defs = fields.fields(ctx.projectId()).stream().filter(d -> !d.archived()).toList();
        Map<String, Object> current = currentValues(view);
        List<Map<String, Object>> transitions = new ArrayList<>();
        for (IssueView.TransitionView t : view.transitions()) {
            List<Map<String, Object>> required = new ArrayList<>();
            for (String field : ruleableFields(defs)) {
                if (workflow.access(field, t.to().id(), ctx.roleIds()) == FieldAccess.REQUIRED) {
                    Object value = current.get(field);
                    required.add(Map.of("field", field, "empty", FieldCatalog.isEmpty(value)));
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id());
            m.put("name", t.name());
            m.put("to", ToolSupport.status(t.to()));
            m.put("requiredFieldsInTarget", required);
            transitions.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("issueKey", key);
        result.put("currentStatus", ToolSupport.status(view.status()));
        result.put("version", view.version());
        result.put("transitions", transitions);
        if (transitions.isEmpty()) {
            result.put("note", view.permissions().transition() ? "The workflow allows no transition from this status for your roles."
                    : "You do not have permission to transition issues in this project.");
        }
        return result;
    }

    static List<String> ruleableFields(List<FieldDefinition> defs) {
        List<String> result = new ArrayList<>(Workflow.SYSTEM_FIELDS.stream().sorted().toList());
        defs.forEach(d -> result.add(d.ruleKey()));
        return result;
    }

    /** Current values by rule key, as far as the viewer can see them. */
    static Map<String, Object> currentValues(IssueView v) {
        Map<String, Object> values = new HashMap<>();
        values.put("description", v.description());
        values.put("assignee", v.assignee());
        values.put("priority", v.priority() == Priority.NONE ? null : v.priority());
        values.put("labels", v.labels());
        values.put("dueDate", v.dueDate());
        values.put("startDate", v.startDate());
        values.put("estimate", v.estimate());
        values.put("milestone", v.milestone());
        values.put("parent", v.parent());
        v.customValues().forEach((k, value) -> values.put("cf:" + k, value));
        return values;
    }

    private Object listViews(AgentRequest request, Args args) {
        List<SavedViews.View> list = views.list(request.org(), args.projectKey("projectKey", false));
        return Map.of("filters", list.stream().map(v -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", v.id());
            m.put("name", v.name());
            m.put("kind", v.kind());
            m.put("project", v.projectKey());
            m.put("shared", v.shared());
            m.put("owner", v.ownerName());
            return m;
        }).toList());
    }

    private Object runView(AgentRequest request, Args args) {
        SavedViews.View view = views.get(request.org(), args.uuid("filterId"));
        IssueFilter filter = view.query() == null ? IssueFilter.empty() : view.query();
        if (view.projectKey() != null) {
            filter = filter.withProjects(List.of(view.projectKey()));
        }
        Map<String, Object> result = page(request, search.search(request.org(), filter, args.integer("page", 0, 0, 1000),
                args.integer("pageSize", 20, 1, 50)));
        result.put("filter", view.name());
        return result;
    }
}
