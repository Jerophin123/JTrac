package com.jtac.utcp.internal;

import com.jtac.attachment.IssueAttachments;
import com.jtac.collaboration.IssueComments;
import com.jtac.identity.UserDirectory;
import com.jtac.identity.UserSummary;
import com.jtac.issue.IssueAccess;
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
import com.jtac.shared.web.ApiErrors;
import com.jtac.shared.web.PageResponse;
import com.jtac.workflow.Status;
import com.jtac.workflow.StatusCategory;
import com.jtac.workflow.Workflow;
import com.jtac.workflow.WorkflowCatalog;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Catalogue of read-only native tools exposed to UTCP clients.
 * All tools enforce JTRAC tenant isolation, issue-level permissions, and project access rules.
 */
@Component
class UtcpToolCatalogue {

    private static final List<String> CATEGORIES = Arrays.stream(StatusCategory.values()).map(Enum::name).toList();
    private static final List<String> PRIORITIES = Arrays.stream(Priority.values()).map(Enum::name).toList();
    private static final List<String> SORTS = List.of("updated", "created", "priority", "dueDate", "key", "title", "status");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final IssueSearch search;
    private final IssueDetails details;
    private final IssueAccess issueAccess;
    private final IssueComments comments;
    private final IssueAttachments attachments;
    private final ProjectAccess projectAccess;
    private final ProjectCatalog projectCatalog;
    private final WorkflowCatalog workflowCatalog;
    private final UserDirectory userDirectory;
    private final JdbcClient jdbc;
    private final JsonMapper json;

    UtcpToolCatalogue(
            IssueSearch search,
            IssueDetails details,
            IssueAccess issueAccess,
            IssueComments comments,
            IssueAttachments attachments,
            ProjectAccess projectAccess,
            ProjectCatalog projectCatalog,
            WorkflowCatalog workflowCatalog,
            UserDirectory userDirectory,
            JdbcClient jdbc,
            JsonMapper json
    ) {
        this.search = search;
        this.details = details;
        this.issueAccess = issueAccess;
        this.comments = comments;
        this.attachments = attachments;
        this.projectAccess = projectAccess;
        this.projectCatalog = projectCatalog;
        this.workflowCatalog = workflowCatalog;
        this.userDirectory = userDirectory;
        this.jdbc = jdbc;
        this.json = json;
    }

    List<UtcpToolDef> tools() {
        List<UtcpToolDef> list = new ArrayList<>();

        // ---------------- A. Issues ----------------
        list.add(new UtcpToolDef(
                "search_issues",
                "Search accessible issues matching full-text queries, projects, assignees, priorities, labels, and status filters.",
                "Issues",
                "issues:read",
                UtcpToolDef.Schema.object()
                        .str("query", "Full-text query over title and description", 300, false)
                        .strings("projectKeys", "List of project keys to filter", 20, 20)
                        .enums("statusCategories", "Status category filters (OPEN, IN_PROGRESS, RESOLVED, CLOSED)", CATEGORIES)
                        .str("assignee", "'me', 'none', or a user handle", 40, false)
                        .str("reporter", "'me' or a user handle", 40, false)
                        .enums("priorities", "Priority filters (LOW, NORMAL, HIGH, URGENT)", PRIORITIES)
                        .strings("labels", "Label names to filter", 20, 60)
                        .bool("resolved", "true for only resolved/closed issues; false for open issues", false)
                        .str("updatedSince", "ISO timestamp to filter issues updated since", 40, false)
                        .strEnum("sort", "Sort field", SORTS, false)
                        .strEnum("direction", "asc or desc", List.of("asc", "desc"), false)
                        .integer("page", "Zero-based page number", 0, 1000, false)
                        .integer("pageSize", "Number of results per page (1-50)", 1, 50, false)
                        .build(),
                Map.of("type", "object", "properties", Map.of("items", Map.of("type", "array"), "totalCount", Map.of("type", "integer"))),
                this::searchIssues
        ));

        list.add(new UtcpToolDef(
                "get_issue",
                "Get full details for an issue including title, description, status, people, labels, custom fields, and permissions.",
                "Issues",
                "issues:read",
                UtcpToolDef.Schema.object()
                        .str("issueKey", "Issue key, e.g. WEB-12", 32, true)
                        .integer("maxDescriptionChars", "Max description characters to return (default 20000)", 200, 100000, false)
                        .build(),
                Map.of("type", "object", "properties", Map.of("key", Map.of("type", "string"), "title", Map.of("type", "string"))),
                this::getIssue
        ));

        list.add(new UtcpToolDef(
                "get_issue_status",
                "Retrieve the current status, status category, and resolution state of an issue.",
                "Issues",
                "issues:read",
                UtcpToolDef.Schema.object()
                        .str("issueKey", "Issue key, e.g. WEB-12", 32, true)
                        .build(),
                Map.of("type", "object", "properties", Map.of("status", Map.of("type", "object"), "resolved", Map.of("type", "boolean"))),
                this::getIssueStatus
        ));

        list.add(new UtcpToolDef(
                "get_issue_history",
                "Retrieve the attributed changelog and history of an issue.",
                "Issues",
                "issues:read",
                UtcpToolDef.Schema.object()
                        .str("issueKey", "Issue key, e.g. WEB-12", 32, true)
                        .str("field", "Only changes to this field (optional)", 60, false)
                        .str("kind", "Only events of this kind (optional)", 40, false)
                        .integer("limit", "Maximum events to return (default 50)", 1, 200, false)
                        .build(),
                Map.of("type", "object", "properties", Map.of("entries", Map.of("type", "array"))),
                this::getIssueHistory
        ));

        list.add(new UtcpToolDef(
                "get_issue_activity",
                "Retrieve recent chronological activity entries on an issue.",
                "Issues",
                "issues:read",
                UtcpToolDef.Schema.object()
                        .str("issueKey", "Issue key, e.g. WEB-12", 32, true)
                        .integer("limit", "Maximum activity items to return (default 50)", 1, 100, false)
                        .build(),
                Map.of("type", "object", "properties", Map.of("activities", Map.of("type", "array"))),
                this::getIssueActivity
        ));

        list.add(new UtcpToolDef(
                "get_issue_relationships",
                "Retrieve links, parent issue, and subtasks associated with an issue.",
                "Issues",
                "issues:read",
                UtcpToolDef.Schema.object()
                        .str("issueKey", "Issue key, e.g. WEB-12", 32, true)
                        .build(),
                Map.of("type", "object", "properties", Map.of("links", Map.of("type", "array"), "subtasks", Map.of("type", "array"))),
                this::getIssueRelationships
        ));

        // ---------------- B. Projects ----------------
        list.add(new UtcpToolDef(
                "list_projects",
                "List all projects in the organization that the caller is permitted to browse.",
                "Projects",
                "projects:read",
                UtcpToolDef.Schema.object()
                        .str("query", "Text filter for project key or name", 80, false)
                        .bool("includeArchived", "Include archived projects (default false)", false)
                        .build(),
                Map.of("type", "object", "properties", Map.of("projects", Map.of("type", "array"))),
                this::listProjects
        ));

        list.add(new UtcpToolDef(
                "get_project",
                "Get detailed information about a project including description, issue types, labels, custom fields, and milestones.",
                "Projects",
                "projects:read",
                UtcpToolDef.Schema.object()
                        .str("projectKey", "Project key, e.g. WEB", 20, true)
                        .build(),
                Map.of("type", "object", "properties", Map.of("project", Map.of("type", "object"))),
                this::getProject
        ));

        list.add(new UtcpToolDef(
                "get_project_metadata",
                "Retrieve project metadata, issue type definitions, custom fields and milestone summaries.",
                "Projects",
                "projects:read",
                UtcpToolDef.Schema.object()
                        .str("projectKey", "Project key, e.g. WEB", 20, true)
                        .build(),
                Map.of("type", "object", "properties", Map.of("metadata", Map.of("type", "object"))),
                this::getProjectMetadata
        ));

        // ---------------- C. Comments ----------------
        list.add(new UtcpToolDef(
                "list_issue_comments",
                "List comments on an issue in chronological order.",
                "Comments",
                "issues:read",
                UtcpToolDef.Schema.object()
                        .str("issueKey", "Issue key", 32, true)
                        .str("contains", "Substring filter for comment body", 200, false)
                        .integer("offset", "Skip this many comments (default 0)", 0, 10000, false)
                        .integer("limit", "Return at most this many comments (default 50)", 1, 200, false)
                        .integer("maxBodyChars", "Truncate each comment to this length (default 4000)", 100, 50000, false)
                        .build(),
                Map.of("type", "object", "properties", Map.of("comments", Map.of("type", "array"))),
                this::listIssueComments
        ));

        list.add(new UtcpToolDef(
                "get_comment_details",
                "Retrieve the full details and body of a specific issue comment.",
                "Comments",
                "issues:read",
                UtcpToolDef.Schema.object()
                        .str("issueKey", "Issue key", 32, true)
                        .str("commentId", "UUID of the comment", 40, true)
                        .build(),
                Map.of("type", "object", "properties", Map.of("comment", Map.of("type", "object"))),
                this::getCommentDetails
        ));

        // ---------------- D. Attachments ----------------
        list.add(new UtcpToolDef(
                "list_issue_attachments",
                "List files attached to an issue with metadata, sizes, verified MIME types and checksums.",
                "Attachments",
                "attachments:read",
                UtcpToolDef.Schema.object()
                        .str("issueKey", "Issue key", 32, true)
                        .build(),
                Map.of("type", "object", "properties", Map.of("attachments", Map.of("type", "array"))),
                this::listIssueAttachments
        ));

        list.add(new UtcpToolDef(
                "get_attachment_metadata",
                "Retrieve metadata for a specific attachment and its authorized download URL.",
                "Attachments",
                "attachments:read",
                UtcpToolDef.Schema.object()
                        .str("issueKey", "Issue key", 32, true)
                        .str("attachmentId", "UUID of the attachment", 40, true)
                        .build(),
                Map.of("type", "object", "properties", Map.of("attachment", Map.of("type", "object"))),
                this::getAttachmentMetadata
        ));

        list.add(new UtcpToolDef(
                "download_attachment",
                "Download an attachment securely. Small text files or images can be retrieved inline with base64/text options.",
                "Attachments",
                "attachments:read",
                UtcpToolDef.Schema.object()
                        .str("issueKey", "Issue key", 32, true)
                        .str("attachmentId", "UUID of the attachment", 40, true)
                        .bool("includeBase64", "Return small non-text files as base64 (up to 500KB)", false)
                        .integer("maxChars", "Characters to return for text files (default 20000)", 100, 60000, false)
                        .build(),
                Map.of("type", "object", "properties", Map.of("downloadUrl", Map.of("type", "string"), "text", Map.of("type", "string"))),
                this::downloadAttachment
        ));

        // ---------------- E. Workflows ----------------
        list.add(new UtcpToolDef(
                "get_workflow",
                "Retrieve the published workflow for a project, including all configured statuses and transitions.",
                "Workflows",
                "projects:read",
                UtcpToolDef.Schema.object()
                        .str("projectKey", "Project key", 20, true)
                        .build(),
                Map.of("type", "object", "properties", Map.of("workflow", Map.of("type", "object"))),
                this::getWorkflow
        ));

        list.add(new UtcpToolDef(
                "get_available_statuses",
                "Retrieve all available issue statuses and their categories for a project.",
                "Workflows",
                "projects:read",
                UtcpToolDef.Schema.object()
                        .str("projectKey", "Project key", 20, true)
                        .build(),
                Map.of("type", "object", "properties", Map.of("statuses", Map.of("type", "array"))),
                this::getAvailableStatuses
        ));

        list.add(new UtcpToolDef(
                "get_permitted_transitions",
                "Retrieve the workflow transitions currently permitted for an issue based on caller permissions.",
                "Workflows",
                "issues:read",
                UtcpToolDef.Schema.object()
                        .str("issueKey", "Issue key", 32, true)
                        .build(),
                Map.of("type", "object", "properties", Map.of("transitions", Map.of("type", "array"))),
                this::getPermittedTransitions
        ));

        // ---------------- F. Users and Teams ----------------
        list.add(new UtcpToolDef(
                "get_user_info",
                "Retrieve authorized public summary information about a user in the organization.",
                "Users and Teams",
                "projects:read",
                UtcpToolDef.Schema.object()
                        .str("userHandle", "User handle, e.g. @janedoe, or 'me' (defaults to 'me')", 40, false)
                        .build(),
                Map.of("type", "object", "properties", Map.of("user", Map.of("type", "object"))),
                this::getUserInfo
        ));

        list.add(new UtcpToolDef(
                "list_accessible_teams",
                "List all accessible teams in the organization.",
                "Users and Teams",
                "projects:read",
                UtcpToolDef.Schema.object().build(),
                Map.of("type", "object", "properties", Map.of("teams", Map.of("type", "array"))),
                this::listAccessibleTeams
        ));

        list.add(new UtcpToolDef(
                "get_team_membership",
                "Retrieve team members for an accessible team in the organization.",
                "Users and Teams",
                "projects:read",
                UtcpToolDef.Schema.object()
                        .str("teamId", "UUID of the team", 40, true)
                        .build(),
                Map.of("type", "object", "properties", Map.of("members", Map.of("type", "array"))),
                this::getTeamMembership
        ));

        // ---------------- G. Reports and Activity ----------------
        list.add(new UtcpToolDef(
                "get_project_activity",
                "Retrieve the recent audit and activity events of a project.",
                "Reports and Activity",
                "reports:read",
                UtcpToolDef.Schema.object()
                        .str("projectKey", "Project key", 20, true)
                        .integer("limit", "Maximum events to return (default 50)", 1, 100, false)
                        .build(),
                Map.of("type", "object", "properties", Map.of("events", Map.of("type", "array"))),
                this::getProjectActivity
        ));

        list.add(new UtcpToolDef(
                "get_issue_statistics",
                "Retrieve aggregated counts of issues grouped by status, category, assignee, priority, type, label or milestone.",
                "Reports and Activity",
                "reports:read",
                UtcpToolDef.Schema.object()
                        .str("projectKey", "Project key", 20, true)
                        .strEnum("groupBy", "Grouping category", IssueSearch.GROUPS.stream().filter(g -> !g.equals("project")).toList(), true)
                        .bool("unresolvedOnly", "Only count open/unresolved issues (default false)", false)
                        .build(),
                Map.of("type", "object", "properties", Map.of("statistics", Map.of("type", "array"))),
                this::getIssueStatistics
        ));

        list.add(new UtcpToolDef(
                "list_fix_reports",
                "Retrieve all fix reports and verification summaries submitted for an issue.",
                "Reports and Activity",
                "reports:read",
                UtcpToolDef.Schema.object()
                        .str("issueKey", "Issue key", 32, true)
                        .build(),
                Map.of("type", "object", "properties", Map.of("reports", Map.of("type", "array"))),
                this::listFixReports
        ));

        list.add(new UtcpToolDef(
                "get_fix_report",
                "Retrieve a specific fix report with its recorded verification tests and command outcomes.",
                "Reports and Activity",
                "reports:read",
                UtcpToolDef.Schema.object()
                        .str("reportId", "Fix report UUID", 40, true)
                        .build(),
                Map.of("type", "object", "properties", Map.of("report", Map.of("type", "object"))),
                this::getFixReport
        ));

        return Collections.unmodifiableList(list);
    }

    // ---------------- Handler Implementations ----------------

    private Object searchIssues(UtcpContext ctx, Map<String, Object> args) {
        String query = optString(args, "query");
        List<String> projectKeys = optStringList(args, "projectKeys");
        List<StatusCategory> categories = optEnumList(args, "statusCategories", StatusCategory.class);
        List<Priority> priorities = optEnumList(args, "priorities", Priority.class);
        List<String> assignees = resolveAssigneeList(ctx, optString(args, "assignee"));
        List<UUID> reporters = resolveReporterList(ctx, optString(args, "reporter"));
        Boolean resolved = optBoolean(args, "resolved");
        Instant updatedSince = optInstant(args, "updatedSince");
        String sort = optString(args, "sort");
        String direction = optString(args, "direction");
        int page = optInt(args, "page", 0);
        int pageSize = Math.min(50, Math.max(1, optInt(args, "pageSize", 20)));

        IssueFilter filter = new IssueFilter(
                projectKeys, query, null, categories, assignees, reporters, priorities,
                null, null, null, null, null, updatedSince, resolved, null, null, null,
                sort != null ? sort : "updated", direction != null ? direction : "desc"
        );

        PageResponse<IssueSummary> res = search.search(ctx.org(), filter, page, pageSize);
        List<Map<String, Object>> items = res.items().stream().map(this::summaryMap).toList();
        return Map.of(
                "items", items,
                "totalCount", res.total(),
                "page", res.page(),
                "pageSize", res.size()
        );
    }

    private Object getIssue(UtcpContext ctx, Map<String, Object> args) {
        String key = reqString(args, "issueKey");
        int maxChars = optInt(args, "maxDescriptionChars", 20000);
        IssueView v = details.view(ctx.org(), key);

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", v.id());
        map.put("key", v.key());
        map.put("title", v.title());
        map.put("description", clip(v.description(), maxChars));
        String projectKey = v.key().contains("-") ? v.key().substring(0, v.key().lastIndexOf('-')) : "";
        map.put("project", Map.of("id", v.project().id(), "name", v.project().name(), "key", projectKey));
        map.put("type", v.type() != null ? Map.of("name", v.type().name(), "color", v.type().color()) : null);
        map.put("status", v.status() != null ? Map.of("name", v.status().name(), "category", v.status().category()) : null);
        map.put("priority", v.priority() != null ? v.priority().name() : null);
        map.put("reporter", personMap(v.reporter()));
        map.put("assignee", personMap(v.assignee()));
        map.put("labels", v.labels().stream().map(IssueSummary.LabelRef::name).toList());
        map.put("startDate", v.startDate());
        map.put("dueDate", v.dueDate());
        map.put("createdAt", v.createdAt());
        map.put("updatedAt", v.updatedAt());
        map.put("resolvedAt", v.resolvedAt());
        map.put("version", v.version());
        map.put("customFields", v.customValues());
        return map;
    }

    private Object getIssueStatus(UtcpContext ctx, Map<String, Object> args) {
        String key = reqString(args, "issueKey");
        IssueView v = details.view(ctx.org(), key);
        return Map.of(
                "issueKey", v.key(),
                "status", v.status() != null ? Map.of("name", v.status().name(), "category", v.status().category()) : null,
                "resolved", v.resolvedAt() != null
        );
    }

    private Object getIssueHistory(UtcpContext ctx, Map<String, Object> args) {
        String key = reqString(args, "issueKey");
        String fieldFilter = optString(args, "field");
        String kindFilter = optString(args, "kind");
        int limit = Math.min(200, Math.max(1, optInt(args, "limit", 50)));

        List<IssueActivityEntry> activities = details.activity(ctx.org(), key);
        List<Map<String, Object>> entries = activities.stream()
                .filter(a -> kindFilter == null || a.kind().equalsIgnoreCase(kindFilter))
                .filter(a -> fieldFilter == null || (a.field() != null && a.field().equalsIgnoreCase(fieldFilter)))
                .limit(limit)
                .map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("occurredAt", a.at());
                    m.put("authorName", a.actor() != null ? a.actor().displayName() : "System");
                    m.put("authorHandle", a.actor() != null ? a.actor().handle() : "");
                    m.put("kind", a.kind());
                    m.put("field", a.field());
                    m.put("oldValue", a.oldValue());
                    m.put("newValue", a.newValue());
                    return m;
                })
                .toList();

        return Map.of("issueKey", key, "entries", entries);
    }

    private Object getIssueActivity(UtcpContext ctx, Map<String, Object> args) {
        String key = reqString(args, "issueKey");
        int limit = Math.min(100, Math.max(1, optInt(args, "limit", 50)));
        List<IssueActivityEntry> activities = details.activity(ctx.org(), key);
        return Map.of("issueKey", key, "activities", activities.stream().limit(limit).toList());
    }

    private Object getIssueRelationships(UtcpContext ctx, Map<String, Object> args) {
        String key = reqString(args, "issueKey");
        IssueView v = details.view(ctx.org(), key);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("issueKey", v.key());
        result.put("parent", v.parent() != null ? Map.of("key", v.parent().key(), "title", v.parent().title()) : null);
        result.put("subtasks", v.subtasks().stream().map(s -> Map.of("key", s.key(), "title", s.title(), "status", s.status() != null ? s.status().name() : "")).toList());
        result.put("links", v.links().stream().map(l -> Map.of("key", l.key(), "title", l.title(), "type", l.type(), "direction", l.direction())).toList());
        return result;
    }

    private Object listProjects(UtcpContext ctx, Map<String, Object> args) {
        String q = optString(args, "query");
        boolean includeArchived = optBoolean(args, "includeArchived", false);

        List<ProjectContext> visible = projectAccess.visible(ctx.org());
        List<Map<String, Object>> res = visible.stream()
                .filter(p -> includeArchived || !p.project().archived())
                .filter(p -> q == null || p.project().name().toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))
                        || p.project().key().toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)))
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", p.projectId());
                    m.put("key", p.project().key());
                    m.put("name", p.project().name());
                    m.put("archived", p.project().archived());
                    return m;
                })
                .toList();

        return Map.of("projects", res);
    }

    private Object getProject(UtcpContext ctx, Map<String, Object> args) {
        String key = reqString(args, "projectKey");
        ProjectContext p = projectAccess.require(ctx.org(), key);
        Optional<String> desc = projectCatalog.description(p.projectId());

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", p.projectId());
        map.put("key", p.project().key());
        map.put("name", p.project().name());
        map.put("description", desc.orElse(""));
        map.put("archived", p.project().archived());
        map.put("issueTypes", projectCatalog.issueTypes(p.projectId()).stream().map(ProjectCatalog.IssueType::name).toList());
        map.put("labels", projectCatalog.labels(p.projectId()).stream().map(ProjectCatalog.Label::name).toList());
        map.put("milestones", projectCatalog.milestones(p.projectId()).stream().map(m -> Map.of("name", m.name(), "state", m.state())).toList());
        return Map.of("project", map);
    }

    private Object getProjectMetadata(UtcpContext ctx, Map<String, Object> args) {
        String key = reqString(args, "projectKey");
        ProjectContext p = projectAccess.require(ctx.org(), key);

        return Map.of(
                "projectKey", p.project().key(),
                "roles", projectCatalog.roles(p.projectId()).stream().map(ProjectCatalog.Role::name).toList(),
                "issueTypes", projectCatalog.issueTypes(p.projectId()).stream().map(t -> Map.of("name", t.name(), "subtask", t.subtask())).toList(),
                "labels", projectCatalog.labels(p.projectId()).stream().map(ProjectCatalog.Label::name).toList(),
                "milestones", projectCatalog.milestones(p.projectId()).stream().map(m -> Map.of("name", m.name(), "dueDate", Objects.toString(m.dueDate(), ""))).toList()
        );
    }

    private Object listIssueComments(UtcpContext ctx, Map<String, Object> args) {
        String key = reqString(args, "issueKey");
        String contains = optString(args, "contains");
        int offset = Math.max(0, optInt(args, "offset", 0));
        int limit = Math.min(200, Math.max(1, optInt(args, "limit", 50)));
        int maxBody = optInt(args, "maxBodyChars", 4000);

        IssueHandle handle = issueAccess.require(ctx.org(), key);
        List<IssueComments.Comment> all = comments.list(handle);
        List<Map<String, Object>> filtered = all.stream()
                .filter(c -> contains == null || c.body().toLowerCase(Locale.ROOT).contains(contains.toLowerCase(Locale.ROOT)))
                .skip(offset)
                .limit(limit)
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.id());
                    m.put("authorName", c.authorName());
                    m.put("authorHandle", c.authorHandle());
                    m.put("createdAt", c.createdAt());
                    m.put("body", clip(c.body(), maxBody));
                    return m;
                })
                .toList();

        return Map.of("issueKey", key, "comments", filtered, "totalMatching", all.size());
    }

    private Object getCommentDetails(UtcpContext ctx, Map<String, Object> args) {
        String key = reqString(args, "issueKey");
        UUID commentId = reqUuid(args, "commentId");
        IssueHandle handle = issueAccess.require(ctx.org(), key);

        IssueComments.Comment found = comments.list(handle).stream()
                .filter(c -> c.id().equals(commentId))
                .findFirst()
                .orElseThrow(() -> ApiErrors.notFound("No comment with id " + commentId + " on issue " + key));

        return Map.of(
                "issueKey", key,
                "comment", Map.of(
                        "id", found.id(),
                        "authorName", found.authorName() != null ? found.authorName() : "",
                        "authorHandle", found.authorHandle() != null ? found.authorHandle() : "",
                        "createdAt", found.createdAt(),
                        "body", found.body()
                )
        );
    }

    private Object listIssueAttachments(UtcpContext ctx, Map<String, Object> args) {
        String key = reqString(args, "issueKey");
        IssueHandle handle = issueAccess.require(ctx.org(), key);
        List<IssueAttachments.Attachment> list = attachments.list(handle);

        List<Map<String, Object>> mapped = list.stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.id());
            m.put("fileName", a.fileName());
            m.put("contentType", a.contentType());
            m.put("sizeBytes", a.sizeBytes());
            m.put("sha256", a.sha256());
            m.put("uploadedBy", a.uploadedBy());
            m.put("createdAt", a.createdAt());
            return m;
        }).toList();

        return Map.of("issueKey", key, "attachments", mapped);
    }

    private Object getAttachmentMetadata(UtcpContext ctx, Map<String, Object> args) {
        String key = reqString(args, "issueKey");
        UUID attId = reqUuid(args, "attachmentId");
        IssueHandle handle = issueAccess.require(ctx.org(), key);

        IssueAttachments.Attachment att = attachments.list(handle).stream()
                .filter(a -> a.id().equals(attId))
                .findFirst()
                .orElseThrow(() -> ApiErrors.notFound("No attachment with id " + attId + " on issue " + key));

        String downloadUrl = "/api/v1/utcp/attachments/" + key + "/" + att.id() + "/download";
        return Map.of(
                "attachment", Map.of(
                        "id", att.id(),
                        "fileName", att.fileName(),
                        "contentType", att.contentType(),
                        "sizeBytes", att.sizeBytes(),
                        "sha256", att.sha256(),
                        "uploadedBy", att.uploadedBy() != null ? att.uploadedBy() : "",
                        "createdAt", att.createdAt(),
                        "downloadUrl", downloadUrl
                )
        );
    }

    private Object downloadAttachment(UtcpContext ctx, Map<String, Object> args) {
        String key = reqString(args, "issueKey");
        UUID attId = reqUuid(args, "attachmentId");
        boolean base64 = optBoolean(args, "includeBase64", false);
        int maxChars = optInt(args, "maxChars", 20000);

        IssueHandle handle = issueAccess.require(ctx.org(), key);
        IssueAttachments.Content content = attachments.read(handle, attId, 500000);

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("attachmentId", attId);
        map.put("fileName", content.attachment().fileName());
        map.put("contentType", content.attachment().contentType());
        map.put("sizeBytes", content.attachment().sizeBytes());
        map.put("downloadUrl", "/api/v1/utcp/attachments/" + key + "/" + attId + "/download");

        String mime = content.attachment().contentType();
        boolean isText = mime.startsWith("text/") || mime.equals("application/json") || mime.equals("application/xml");
        if (isText && content.bytes() != null) {
            String full = new String(content.bytes(), StandardCharsets.UTF_8);
            map.put("textContent", clip(full, maxChars));
        } else if (base64 && content.bytes() != null) {
            map.put("base64Content", Base64.getEncoder().encodeToString(content.bytes()));
        }

        return map;
    }

    private Object getWorkflow(UtcpContext ctx, Map<String, Object> args) {
        String key = reqString(args, "projectKey");
        ProjectContext p = projectAccess.require(ctx.org(), key);
        Workflow wf = workflowCatalog.published(p.projectId());

        List<Map<String, Object>> statusMaps = wf.boardStatuses().stream()
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", s.id());
                    m.put("name", s.name());
                    m.put("category", s.category().name());
                    return m;
                })
                .toList();

        List<Map<String, Object>> transitions = wf.definition().transitions().stream()
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", t.id());
                    m.put("name", t.name());
                    m.put("from", t.from());
                    m.put("to", t.to());
                    return m;
                })
                .toList();

        return Map.of("workflow", Map.of(
                "projectKey", p.project().key(),
                "initialStatusId", wf.initialStatusId(),
                "statuses", statusMaps,
                "transitions", transitions
        ));
    }

    private Object getAvailableStatuses(UtcpContext ctx, Map<String, Object> args) {
        String key = reqString(args, "projectKey");
        ProjectContext p = projectAccess.require(ctx.org(), key);
        List<Status> statuses = workflowCatalog.statuses(p.projectId());

        List<Map<String, Object>> res = statuses.stream()
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", s.id());
                    m.put("name", s.name());
                    m.put("category", s.category().name());
                    return m;
                })
                .toList();

        return Map.of("projectKey", p.project().key(), "statuses", res);
    }

    private Object getPermittedTransitions(UtcpContext ctx, Map<String, Object> args) {
        String key = reqString(args, "issueKey");
        IssueView v = details.view(ctx.org(), key);

        List<Map<String, Object>> trans = v.transitions().stream()
                .map(t -> Map.of(
                        "transitionId", t.id(),
                        "name", t.name(),
                        "targetStatus", Map.of("name", t.to().name(), "category", t.to().category())
                ))
                .toList();

        return Map.of("issueKey", v.key(), "currentStatus", v.status().name(), "transitions", trans);
    }

    private Object getUserInfo(UtcpContext ctx, Map<String, Object> args) {
        String handle = optString(args, "userHandle");
        if (handle == null || handle.isBlank() || handle.equalsIgnoreCase("me")) {
            return Map.of("user", Map.of(
                    "id", ctx.userId(),
                    "displayName", ctx.user().displayName(),
                    "handle", ctx.user().handle(),
                    "organization", ctx.org().slug()
            ));
        }

        String clean = handle.startsWith("@") ? handle.substring(1) : handle;
        Map<String, UserSummary> found = userDirectory.findByHandles(List.of(clean.toLowerCase(Locale.ROOT)));
        UserSummary user = found.get(clean.toLowerCase(Locale.ROOT));
        if (user == null) {
            throw ApiErrors.notFound("No user with handle @" + clean);
        }

        return Map.of("user", Map.of(
                "id", user.id(),
                "displayName", user.displayName(),
                "handle", user.handle()
        ));
    }

    private Object listAccessibleTeams(UtcpContext ctx, Map<String, Object> args) {
        List<Map<String, Object>> teams = jdbc.sql("""
                SELECT t.id, t.name, t.description,
                       (SELECT count(*) FROM team_member tm WHERE tm.team_id = t.id) AS member_count
                  FROM team t
                 WHERE t.organization_id = :org
                 ORDER BY lower(t.name)
                """)
                .param("org", ctx.organizationId())
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id", UUID.class));
                    m.put("name", rs.getString("name"));
                    m.put("description", rs.getString("description"));
                    m.put("memberCount", rs.getInt("member_count"));
                    return m;
                })
                .list();

        return Map.of("teams", teams);
    }

    private Object getTeamMembership(UtcpContext ctx, Map<String, Object> args) {
        UUID teamId = reqUuid(args, "teamId");
        Map<String, Object> team = jdbc.sql("""
                SELECT id, name, description FROM team WHERE id = :id AND organization_id = :org
                """)
                .param("id", teamId)
                .param("org", ctx.organizationId())
                .query((rs, n) -> Map.<String, Object>of(
                        "id", rs.getObject("id", UUID.class),
                        "name", rs.getString("name"),
                        "description", rs.getString("description") != null ? rs.getString("description") : ""
                ))
                .optional()
                .orElseThrow(() -> ApiErrors.notFound("Team not found in this organization."));

        List<Map<String, Object>> members = jdbc.sql("""
                SELECT u.id, u.display_name, u.handle
                  FROM team_member tm JOIN user_account u ON u.id = tm.user_id
                 WHERE tm.team_id = :team AND tm.organization_id = :org
                 ORDER BY lower(u.display_name)
                """)
                .param("team", teamId)
                .param("org", ctx.organizationId())
                .query((rs, n) -> Map.<String, Object>of(
                        "id", rs.getObject("id", UUID.class),
                        "displayName", rs.getString("display_name"),
                        "handle", rs.getString("handle")
                ))
                .list();

        return Map.of("team", team, "members", members);
    }

    private Object getProjectActivity(UtcpContext ctx, Map<String, Object> args) {
        String key = reqString(args, "projectKey");
        int limit = Math.min(100, Math.max(1, optInt(args, "limit", 50)));
        ProjectContext p = projectAccess.require(ctx.org(), key);

        List<Map<String, Object>> events = jdbc.sql("""
                SELECT a.occurred_at, a.action, a.target_type, a.target_id, a.details::text AS details,
                       coalesce(u.display_name, 'System') AS actor_name
                  FROM audit_event a
                  LEFT JOIN user_account u ON u.id = a.actor_id
                 WHERE a.organization_id = :org
                   AND (a.target_type = 'project' AND a.target_id = :pid
                        OR a.details ->> 'projectKey' = :key)
                 ORDER BY a.occurred_at DESC
                 LIMIT :limit
                """)
                .param("org", ctx.organizationId())
                .param("pid", p.projectId().toString())
                .param("key", p.project().key())
                .param("limit", limit)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("occurredAt", rs.getObject("occurred_at", OffsetDateTime.class).toInstant());
                    m.put("actorName", rs.getString("actor_name"));
                    m.put("action", rs.getString("action"));
                    m.put("targetType", rs.getString("target_type"));
                    m.put("targetId", rs.getString("target_id"));
                    try {
                        m.put("details", json.readValue(rs.getString("details"), MAP_TYPE));
                    } catch (Exception e) {
                        m.put("details", rs.getString("details"));
                    }
                    return m;
                })
                .list();

        return Map.of("projectKey", p.project().key(), "events", events);
    }

    private Object getIssueStatistics(UtcpContext ctx, Map<String, Object> args) {
        String key = reqString(args, "projectKey");
        String groupBy = optString(args, "groupBy");
        if (groupBy == null || groupBy.isBlank()) {
            groupBy = "status";
        }
        boolean unresolvedOnly = optBoolean(args, "unresolvedOnly", false);

        ProjectContext p = projectAccess.require(ctx.org(), key);
        IssueFilter filter = IssueFilter.empty().withProjects(List.of(p.project().key()));
        if (unresolvedOnly) {
            filter = new IssueFilter(
                    List.of(p.project().key()), null, null, null, null, null, null, null, null,
                    null, null, null, null, false, null, null, null, null, null
            );
        }

        List<IssueSearch.Bucket> buckets = search.countBy(ctx.org(), filter, groupBy);
        List<Map<String, Object>> stats = buckets.stream().map(b -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", b.key() != null ? b.key() : "none");
            m.put("label", b.label() != null ? b.label() : "None");
            m.put("count", b.count());
            m.put("estimate", b.estimate());
            return m;
        }).toList();

        return Map.of("projectKey", p.project().key(), "groupBy", groupBy, "statistics", stats);
    }

    private Object listFixReports(UtcpContext ctx, Map<String, Object> args) {
        String key = reqString(args, "issueKey");
        IssueHandle handle = issueAccess.require(ctx.org(), key);

        List<Map<String, Object>> reports = jdbc.sql("""
                SELECT r.id, r.summary, r.root_cause, r.created_at, u.display_name AS submitted_by
                  FROM mcp_fix_report r
                  LEFT JOIN user_account u ON u.id = r.author_id
                 WHERE r.issue_id = :iid AND r.organization_id = :org
                 ORDER BY r.created_at DESC
                """)
                .param("iid", handle.issueId())
                .param("org", ctx.organizationId())
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id", UUID.class));
                    m.put("summary", rs.getString("summary"));
                    m.put("rootCause", rs.getString("root_cause"));
                    m.put("submittedBy", rs.getString("submitted_by"));
                    m.put("createdAt", rs.getObject("created_at", OffsetDateTime.class).toInstant());
                    return m;
                })
                .list();

        return Map.of("issueKey", key, "reports", reports);
    }

    private Object getFixReport(UtcpContext ctx, Map<String, Object> args) {
        UUID reportId = reqUuid(args, "reportId");

        Map<String, Object> report = jdbc.sql("""
                SELECT r.id, r.summary, r.root_cause, r.details::text AS details, r.created_at,
                       u.display_name, p.key || '-' || i.number AS issue_key
                  FROM mcp_fix_report r
                  JOIN issue i ON i.id = r.issue_id AND i.organization_id = r.organization_id
                  JOIN project p ON p.id = i.project_id
                  LEFT JOIN user_account u ON u.id = r.author_id
                 WHERE r.id = :id AND r.organization_id = :org
                """)
                .param("id", reportId)
                .param("org", ctx.organizationId())
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id", UUID.class));
                    m.put("issueKey", rs.getString("issue_key"));
                    m.put("summary", rs.getString("summary"));
                    m.put("rootCause", rs.getString("root_cause"));
                    m.put("createdAt", rs.getObject("created_at", OffsetDateTime.class).toInstant());
                    m.put("submittedBy", rs.getString("display_name"));
                    try {
                        m.putAll(json.readValue(rs.getString("details"), MAP_TYPE));
                    } catch (Exception ignored) {}
                    return m;
                })
                .optional()
                .orElseThrow(() -> ApiErrors.notFound("No fix report with id " + reportId));

        // Re-check issue access for caller
        issueAccess.require(ctx.org(), (String) report.get("issueKey"));

        List<Map<String, Object>> verifications = jdbc.sql("""
                SELECT id, outcome, command, details, created_at
                  FROM mcp_verification
                 WHERE report_id = :rid
                 ORDER BY created_at
                """)
                .param("rid", reportId)
                .query((rs, n) -> Map.<String, Object>of(
                        "id", rs.getObject("id", UUID.class),
                        "outcome", rs.getString("outcome"),
                        "command", rs.getString("command") != null ? rs.getString("command") : "",
                        "details", rs.getString("details") != null ? rs.getString("details") : "",
                        "createdAt", rs.getObject("created_at", OffsetDateTime.class).toInstant()
                ))
                .list();

        report.put("verifications", verifications);
        return Map.of("report", report);
    }

    // ---------------- Helper Methods ----------------

    private Map<String, Object> summaryMap(IssueSummary s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("key", s.key());
        m.put("projectKey", s.projectKey());
        m.put("title", s.title());
        m.put("status", s.status() != null ? Map.of("name", s.status().name(), "category", s.status().category()) : null);
        m.put("priority", s.priority() != null ? s.priority().name() : null);
        m.put("assignee", personMap(s.assignee()));
        m.put("reporter", personMap(s.reporter()));
        m.put("updatedAt", s.updatedAt());
        m.put("createdAt", s.createdAt());
        return m;
    }

    private static Map<String, Object> personMap(IssueSummary.Person p) {
        if (p == null) {
            return null;
        }
        return Map.of("displayName", p.displayName(), "handle", p.handle());
    }

    private static String clip(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "\n…[truncated: " + (text.length() - max) + " characters]";
    }

    private static String reqString(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null || val.toString().isBlank()) {
            throw ApiErrors.invalid(key, "Parameter '" + key + "' is required.");
        }
        return val.toString().trim();
    }

    private static UUID reqUuid(Map<String, Object> args, String key) {
        String str = reqString(args, key);
        try {
            return UUID.fromString(str);
        } catch (IllegalArgumentException e) {
            throw ApiErrors.invalid(key, "Parameter '" + key + "' must be a valid UUID.");
        }
    }

    private static String optString(Map<String, Object> args, String key) {
        Object val = args.get(key);
        return val == null ? null : val.toString().trim();
    }

    private static int optInt(Map<String, Object> args, String key, int defaultValue) {
        Object val = args.get(key);
        if (val instanceof Number n) {
            return n.intValue();
        }
        if (val instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    private static Boolean optBoolean(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val instanceof Boolean b) {
            return b;
        }
        if (val instanceof String s && !s.isBlank()) {
            return Boolean.parseBoolean(s.trim());
        }
        return null;
    }

    private static boolean optBoolean(Map<String, Object> args, String key, boolean defaultValue) {
        Boolean b = optBoolean(args, key);
        return b != null ? b : defaultValue;
    }

    private static Instant optInstant(Map<String, Object> args, String key) {
        String str = optString(args, key);
        if (str == null || str.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(str);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> optStringList(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val instanceof List<?> l) {
            return l.stream().map(Object::toString).toList();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Enum<E>> List<E> optEnumList(Map<String, Object> args, String key, Class<E> enumClass) {
        List<String> raw = optStringList(args, key);
        if (raw == null) {
            return null;
        }
        List<E> list = new ArrayList<>();
        for (String s : raw) {
            try {
                list.add(Enum.valueOf(enumClass, s.toUpperCase(Locale.ROOT)));
            } catch (Exception ignored) {}
        }
        return list.isEmpty() ? null : list;
    }

    private List<String> resolveAssigneeList(UtcpContext ctx, String assignee) {
        if (assignee == null || assignee.isBlank()) {
            return null;
        }
        if (assignee.equalsIgnoreCase("me")) {
            return List.of("me");
        }
        if (assignee.equalsIgnoreCase("none")) {
            return List.of("none");
        }
        String handle = assignee.startsWith("@") ? assignee.substring(1) : assignee;
        Map<String, UserSummary> found = userDirectory.findByHandles(List.of(handle.toLowerCase(Locale.ROOT)));
        UserSummary u = found.get(handle.toLowerCase(Locale.ROOT));
        return u != null ? List.of(u.id().toString()) : List.of("none");
    }

    private List<UUID> resolveReporterList(UtcpContext ctx, String reporter) {
        if (reporter == null || reporter.isBlank()) {
            return null;
        }
        if (reporter.equalsIgnoreCase("me")) {
            return List.of(ctx.userId());
        }
        String handle = reporter.startsWith("@") ? reporter.substring(1) : reporter;
        Map<String, UserSummary> found = userDirectory.findByHandles(List.of(handle.toLowerCase(Locale.ROOT)));
        UserSummary u = found.get(handle.toLowerCase(Locale.ROOT));
        return u != null ? List.of(u.id()) : Collections.emptyList();
    }
}
