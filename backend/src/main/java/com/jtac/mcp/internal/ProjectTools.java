package com.jtac.mcp.internal;

import static com.jtac.mcp.internal.ToolDef.Kind.READ;

import com.jtac.field.FieldCatalog;
import com.jtac.field.FieldDefinition;
import com.jtac.identity.UserDirectory;
import com.jtac.identity.UserSummary;
import com.jtac.issue.IssueFilter;
import com.jtac.issue.IssueSearch;
import com.jtac.notification.Inbox;
import com.jtac.planning.MilestoneProgress;
import com.jtac.project.ProjectAccess;
import com.jtac.project.ProjectCatalog;
import com.jtac.project.ProjectContext;
import com.jtac.project.ProjectPermission;
import com.jtac.shared.web.PageResponse;
import com.jtac.workflow.FieldAccess;
import com.jtac.workflow.Status;
import com.jtac.workflow.Workflow;
import com.jtac.workflow.WorkflowCatalog;
import com.jtac.workflow.WorkflowDefinition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Project context (scope {@code projects:read}) and the user's notifications (scope {@code notifications:read}). */
@Component
class ProjectTools implements ToolDef.Provider {

    private final ProjectAccess projects;
    private final ProjectCatalog catalog;
    private final WorkflowCatalog workflows;
    private final FieldCatalog fields;
    private final IssueSearch search;
    private final MilestoneProgress milestones;
    private final UserDirectory users;
    private final Inbox inbox;

    ProjectTools(ProjectAccess projects, ProjectCatalog catalog, WorkflowCatalog workflows, FieldCatalog fields,
            IssueSearch search, MilestoneProgress milestones, UserDirectory users, Inbox inbox) {
        this.projects = projects;
        this.catalog = catalog;
        this.workflows = workflows;
        this.fields = fields;
        this.search = search;
        this.milestones = milestones;
        this.users = users;
        this.inbox = inbox;
    }

    @Override
    public List<ToolDef> tools() {
        return List.of(
                new ToolDef("list_projects", "List projects",
                        "Projects you can see in this organization, optionally filtered by a text in key or name.",
                        Scope.PROJECTS_READ, READ, ToolDef.Schema.object()
                                .str("query", "Text in the project key or name", 80, false)
                                .bool("includeArchived", "Include archived projects (default false)").build(), this::list),
                new ToolDef("get_project", "Get project",
                        "A project's description, your permissions in it, issue types, labels, custom fields (with"
                                + " options) and milestones with their progress.",
                        Scope.PROJECTS_READ, READ, ToolDef.Schema.object()
                                .str("projectKey", "Project key, e.g. WEB", 20, true).build(), this::get),
                new ToolDef("get_project_workflow", "Get project workflow",
                        "The project's published workflow: statuses with categories, the initial status, every transition"
                                + " (from, to, which roles may use it) and, for your roles, which fields are hidden,"
                                + " read-only or required in each status. Read from JTRAC, never assumed.",
                        Scope.PROJECTS_READ, READ, ToolDef.Schema.object()
                                .str("projectKey", "Project key", 20, true).build(), this::workflow),
                new ToolDef("get_project_issue_statistics", "Get project issue statistics",
                        "Counts of the project's issues (that you can see) grouped by status, category, assignee,"
                                + " priority, type, label or milestone.",
                        Scope.PROJECTS_READ, READ, ToolDef.Schema.object()
                                .str("projectKey", "Project key", 20, true)
                                .strEnum("groupBy", "Grouping", IssueSearch.GROUPS.stream().filter(g -> !g.equals("project")).toList(), true)
                                .bool("unresolvedOnly", "Only open issues (default false)").build(), this::statistics),
                new ToolDef("list_assignable_users", "List assignable users",
                        "People who can see the project and can therefore be assigned or mentioned: name and handle"
                                + " (no email addresses).",
                        Scope.PROJECTS_READ, READ, ToolDef.Schema.object()
                                .str("projectKey", "Project key", 20, true)
                                .str("query", "Text in the name or handle", 80, false)
                                .integer("limit", "At most this many (default 50)", 1, 200).build(), this::assignable),
                new ToolDef("get_my_notifications", "Get my notifications",
                        "Your in-app notifications in this organization, newest first (assignments, mentions, comments,"
                                + " status changes on issues you watch). Reading them here does not mark them read.",
                        Scope.NOTIFICATIONS_READ, READ, ToolDef.Schema.object()
                                .bool("unreadOnly", "Only unread notifications (default true)")
                                .integer("page", "Zero-based page", 0, 1000)
                                .integer("pageSize", "Per page (default 20)", 1, 50).build(), this::notifications),
                new ToolDef("get_notification_preferences", "Get notification preferences",
                        "Which notification kinds you receive in the app and by email in this organization (read-only).",
                        Scope.NOTIFICATIONS_READ, READ, ToolDef.Schema.object().build(),
                        (request, args) -> Map.of("preferences", inbox.preferences(request.org()))));
    }

    private Object list(AgentRequest request, Args args) {
        String query = args.str("query");
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        boolean archived = args.bool("includeArchived", false);
        return Map.of("projects", projects.visible(request.org()).stream()
                .filter(p -> archived || !p.project().archived())
                .filter(p -> q.isEmpty() || p.project().key().toLowerCase(Locale.ROOT).contains(q)
                        || p.project().name().toLowerCase(Locale.ROOT).contains(q))
                .sorted(Comparator.comparing(p -> p.project().key()))
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("key", p.project().key());
                    m.put("name", p.project().name());
                    m.put("visibility", p.project().visibility());
                    m.put("archived", p.project().archived());
                    return m;
                }).toList());
    }

    private Object get(AgentRequest request, Args args) {
        ProjectContext ctx = projects.require(request.org(), args.projectKey("projectKey", true));
        UUID id = ctx.projectId();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", ctx.project().key());
        result.put("name", ctx.project().name());
        result.put("archived", ctx.project().archived());
        result.put("description", ToolSupport.clip(catalog.description(id).orElse(null), 10_000));
        result.put("yourPermissions", ctx.permissions().stream().sorted().map(Enum::name).toList());
        result.put("issueTypes", catalog.issueTypes(id).stream().filter(t -> !t.archived())
                .map(t -> Map.of("name", t.name(), "subtask", t.subtask())).toList());
        result.put("labels", catalog.labels(id).stream().map(ProjectCatalog.Label::name).toList());
        result.put("customFields", fields.fields(id).stream().filter(f -> !f.archived()).map(ProjectTools::field).toList());
        result.put("milestones", milestones.forProject(request.org(), ctx.project().key(), IssueFilter.empty()).stream().map(m -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", m.name());
            map.put("state", m.state());
            map.put("startDate", m.startDate());
            map.put("dueDate", m.dueDate());
            map.put("issues", m.total());
            map.put("done", m.done());
            map.put("progress", m.progress());
            return map;
        }).toList());
        result.put("contentNotice", ToolSupport.UNTRUSTED);
        return result;
    }

    private static Map<String, Object> field(FieldDefinition f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", f.key());
        m.put("name", f.name());
        m.put("type", f.type());
        m.put("description", f.description());
        if (f.options() != null && !f.options().isEmpty()) {
            m.put("options", f.options().stream().map(FieldDefinition.Option::label).toList());
        }
        return m;
    }

    private Object workflow(AgentRequest request, Args args) {
        ProjectContext ctx = projects.require(request.org(), args.projectKey("projectKey", true));
        Workflow workflow = workflows.published(ctx.projectId());
        Map<UUID, String> roles = new LinkedHashMap<>();
        catalog.roles(ctx.projectId()).forEach(r -> roles.put(r.id(), r.name()));
        List<FieldDefinition> defs = fields.fields(ctx.projectId()).stream().filter(d -> !d.archived()).toList();
        List<String> ruleable = IssueTools.ruleableFields(defs);
        List<Map<String, Object>> statuses = new ArrayList<>();
        for (Status s : workflow.boardStatuses()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name());
            m.put("category", s.category());
            m.put("initial", s.id().equals(workflow.initialStatusId()));
            Map<String, List<String>> forYou = new LinkedHashMap<>();
            for (String field : ruleable) {
                FieldAccess access = workflow.access(field, s.id(), ctx.roleIds());
                if (access != FieldAccess.OPTIONAL) {
                    forYou.computeIfAbsent(access.name(), k -> new ArrayList<>()).add(field);
                }
            }
            m.put("fieldRulesForYou", forYou);
            statuses.add(m);
        }
        List<Map<String, Object>> transitions = new ArrayList<>();
        for (WorkflowDefinition.Transition t : workflow.definition().transitions()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id());
            m.put("name", t.name());
            m.put("from", t.from().isEmpty() ? List.of("any status") : t.from().stream().map(id -> statusName(workflow, id)).toList());
            m.put("to", statusName(workflow, t.to()));
            m.put("roles", t.roles().isEmpty() ? List.of("any role with the transition permission")
                    : t.roles().stream().map(r -> roles.getOrDefault(r, "unknown role")).toList());
            m.put("availableToYou", ctx.has(ProjectPermission.TRANSITION_ISSUES)
                    && (t.roles().isEmpty() || t.roles().stream().anyMatch(ctx.roleIds()::contains)));
            transitions.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectKey", ctx.project().key());
        result.put("workflowVersion", workflow.versionNo());
        result.put("yourRoles", ctx.roleIds().stream().map(r -> roles.getOrDefault(r, "unknown role")).sorted().toList());
        result.put("statuses", statuses);
        result.put("transitions", transitions);
        result.put("note", "Field rules list fields that are not simply optional for your roles; fields not listed are"
                + " optional. Custom fields appear as cf:<key>.");
        return result;
    }

    private static String statusName(Workflow workflow, UUID id) {
        return workflow.status(id).map(Status::name).orElse("retired status");
    }

    private Object statistics(AgentRequest request, Args args) {
        ProjectContext ctx = projects.require(request.org(), args.projectKey("projectKey", true));
        String groupBy = args.required("groupBy");
        IssueFilter filter = IssueFilter.empty().withProjects(List.of(ctx.project().key()));
        if (args.bool("unresolvedOnly", false)) {
            filter = new IssueFilter(filter.projectKeys(), null, null, null, null, null, null, null, null, null, null, null,
                    null, false, null, null, null, null, null);
        }
        List<IssueSearch.Bucket> buckets = search.countBy(request.org(), filter, groupBy);
        return Map.of("projectKey", ctx.project().key(), "groupBy", groupBy, "buckets", buckets.stream()
                .map(b -> Map.of("group", b.label() == null ? "none" : b.label(), "count", b.count())).toList());
    }

    private Object assignable(AgentRequest request, Args args) {
        ProjectContext ctx = projects.require(request.org(), args.projectKey("projectKey", true));
        String query = args.str("query");
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        int limit = args.integer("limit", 50, 1, 200);
        Map<UUID, UserSummary> people = users.findAll(projects.browsers(ctx.organizationId(), ctx.projectId()));
        return Map.of("projectKey", ctx.project().key(), "users", people.values().stream()
                .filter(UserSummary::active)
                .filter(u -> q.isEmpty() || u.displayName().toLowerCase(Locale.ROOT).contains(q)
                        || (u.handle() != null && u.handle().toLowerCase(Locale.ROOT).contains(q)))
                .sorted(Comparator.comparing(UserSummary::displayName, String.CASE_INSENSITIVE_ORDER))
                .limit(limit)
                .map(u -> Map.of("name", u.displayName(), "handle", u.handle() == null ? "" : u.handle())).toList());
    }

    private Object notifications(AgentRequest request, Args args) {
        PageResponse<Inbox.Item> page = inbox.page(request.org(), args.bool("unreadOnly", true),
                args.integer("page", 0, 0, 1000), args.integer("pageSize", 20, 1, 50));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", page.items());
        result.put("total", page.total());
        result.put("page", page.page());
        result.put("contentNotice", ToolSupport.UNTRUSTED);
        return result;
    }
}
