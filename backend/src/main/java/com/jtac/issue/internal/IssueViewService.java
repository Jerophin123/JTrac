package com.jtac.issue.internal;

import com.jtac.field.FieldCatalog;
import com.jtac.field.FieldDefinition;
import com.jtac.identity.UserDirectory;
import com.jtac.identity.UserSummary;
import com.jtac.issue.IssueActivityEntry;
import com.jtac.issue.IssueSummary;
import com.jtac.issue.IssueView;
import com.jtac.issue.IssueView.LinkView;
import com.jtac.issue.IssueView.Permissions;
import com.jtac.issue.IssueView.SubtaskView;
import com.jtac.issue.IssueView.TransitionView;
import com.jtac.issue.Priority;
import com.jtac.organization.OrgContext;
import com.jtac.project.ProjectAccess;
import com.jtac.project.ProjectCatalog;
import com.jtac.project.ProjectContext;
import com.jtac.project.ProjectPermission;
import com.jtac.workflow.FieldAccess;
import com.jtac.workflow.Status;
import com.jtac.workflow.Workflow;
import com.jtac.workflow.WorkflowCatalog;
import com.jtac.workflow.WorkflowDefinition;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Builds the issue detail view for one viewer, applying field rules and link visibility. */
@Service
class IssueViewService {

    private static final TypeReference<Object> ANY = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final IssueStore store;
    private final ProjectAccess access;
    private final ProjectCatalog catalog;
    private final WorkflowCatalog workflows;
    private final FieldCatalog fields;
    private final UserDirectory users;

    IssueViewService(JdbcClient jdbc, JsonMapper json, IssueStore store, ProjectAccess access, ProjectCatalog catalog,
            WorkflowCatalog workflows, FieldCatalog fields, UserDirectory users) {
        this.jdbc = jdbc;
        this.json = json;
        this.store = store;
        this.access = access;
        this.catalog = catalog;
        this.workflows = workflows;
        this.fields = fields;
        this.users = users;
    }

    @Transactional(readOnly = true)
    IssueView view(ProjectContext ctx, IssueRecord issue) {
        Workflow workflow = workflows.published(ctx.projectId());
        List<FieldDefinition> defs = fields.fields(ctx.projectId());
        FieldGuard guard = FieldGuard.of(workflow, issue.statusId(), ctx.roleIds(), defs);
        Status status = workflow.status(issue.statusId()).orElseThrow();

        Set<UUID> people = new HashSet<>();
        people.add(issue.reporterId());
        if (issue.assigneeId() != null) {
            people.add(issue.assigneeId());
        }
        List<UUID> watcherIds = jdbc.sql("SELECT user_id FROM issue_watcher WHERE issue_id = :id").param("id", issue.id())
                .query(UUID.class).list();
        people.addAll(watcherIds);
        List<IssueRecord> children = store.children(issue.id());
        children.forEach(c -> {
            if (c.assigneeId() != null) {
                people.add(c.assigneeId());
            }
        });
        Map<UUID, UserSummary> persons = users.findAll(people);

        Map<String, Object> custom = new LinkedHashMap<>();
        for (FieldDefinition def : defs) {
            if (guard.visible(def.ruleKey()) && issue.customValues().containsKey(def.key())) {
                custom.put(def.key(), issue.customValues().get(def.key()));
            }
        }
        List<IssueSummary.LabelRef> labels = guard.visible("labels")
                ? catalog.labels(ctx.projectId()).stream().filter(l -> issue.labelIds().contains(l.id()))
                        .map(l -> new IssueSummary.LabelRef(l.id(), l.name(), l.color())).toList()
                : null;
        IssueSummary.Ref milestone = !guard.visible("milestone") || issue.milestoneId() == null ? null
                : catalog.milestones(ctx.projectId()).stream().filter(m -> m.id().equals(issue.milestoneId())).findFirst()
                        .map(m -> new IssueSummary.Ref(m.id(), m.name())).orElse(null);
        SubtaskView parent = null;
        if (guard.visible("parent") && issue.parentId() != null) {
            parent = store.byId(ctx.organizationId(), issue.parentId())
                    .map(p -> new SubtaskView(p.key(), p.title(), statusRef(workflow, p.statusId()), null)).orElse(null);
        }
        List<SubtaskView> subtasks = new ArrayList<>();
        for (IssueRecord child : children) {
            FieldGuard childGuard = FieldGuard.of(workflow, child.statusId(), ctx.roleIds(), defs);
            subtasks.add(new SubtaskView(child.key(), child.title(), statusRef(workflow, child.statusId()),
                    childGuard.visible("assignee") ? person(persons, child.assigneeId()) : null));
        }
        List<TransitionView> transitions = new ArrayList<>();
        if (ctx.has(ProjectPermission.TRANSITION_ISSUES)) {
            for (WorkflowDefinition.Transition t : workflow.transitionsFrom(issue.statusId(), ctx.roleIds())) {
                transitions.add(new TransitionView(t.id(), t.name(), statusRef(workflow, t.to())));
            }
        }
        ProjectCatalog.IssueType type = catalog.issueTypes(ctx.projectId()).stream()
                .filter(t -> t.id().equals(issue.typeId())).findFirst().orElseThrow();
        Permissions permissions = new Permissions(ctx.has(ProjectPermission.EDIT_ISSUES),
                ctx.has(ProjectPermission.TRANSITION_ISSUES), ctx.has(ProjectPermission.ASSIGN_ISSUES),
                ctx.has(ProjectPermission.DELETE_ISSUES), ctx.has(ProjectPermission.COMMENT),
                ctx.has(ProjectPermission.MANAGE_ATTACHMENTS), ctx.has(ProjectPermission.MANAGE_PROJECT));
        return new IssueView(issue.id(), issue.key(), new IssueSummary.Ref(ctx.projectId(), ctx.project().key()), type,
                issue.title(), guard.visible("description") ? issue.description() : null,
                new IssueSummary.StatusRef(status.id(), status.name(), status.category().name()),
                guard.visible("priority") ? issue.priority() : null,
                guard.visible("assignee") ? person(persons, issue.assigneeId()) : null,
                person(persons, issue.reporterId()), parent, milestone,
                guard.visible("startDate") ? issue.startDate() : null, guard.visible("dueDate") ? issue.dueDate() : null,
                guard.visible("estimate") ? issue.estimate() : null, labels, custom, guard.access(), transitions, subtasks,
                links(ctx.org(), issue), watcherIds.stream().map(id -> person(persons, id)).toList(),
                watcherIds.contains(ctx.actorId()), permissions, issue.createdAt(), issue.updatedAt(), issue.resolvedAt(),
                issue.version());
    }

    /** Links whose other side the viewer can see. */
    private List<LinkView> links(OrgContext org, IssueRecord issue) {
        record Raw(UUID id, String type, String direction, UUID otherId) {
        }
        List<Raw> raw = jdbc.sql("""
                SELECT id, type, 'outgoing' AS direction, target_id AS other FROM issue_link WHERE source_id = :id
                UNION ALL
                SELECT id, type, 'incoming', source_id FROM issue_link WHERE target_id = :id
                """)
                .param("id", issue.id())
                .query((rs, n) -> new Raw(rs.getObject("id", UUID.class), rs.getString("type"), rs.getString("direction"),
                        rs.getObject("other", UUID.class)))
                .list();
        List<LinkView> result = new ArrayList<>();
        Map<UUID, ProjectContext> projectCache = new LinkedHashMap<>();
        for (Raw link : raw) {
            IssueRecord other = store.byId(org.organizationId(), link.otherId()).orElse(null);
            if (other == null) {
                continue;
            }
            ProjectContext otherCtx = projectCache.computeIfAbsent(other.projectId(),
                    id -> access.visible(org, List.of(id)).get(id));
            if (otherCtx == null) {
                continue;
            }
            Workflow wf = workflows.published(other.projectId());
            result.add(new LinkView(link.id(), link.type(), link.direction(), other.key(), other.title(),
                    statusRef(wf, other.statusId())));
        }
        return result;
    }

    @Transactional(readOnly = true)
    List<IssueActivityEntry> activity(ProjectContext ctx, IssueRecord issue) {
        Workflow workflow = workflows.published(ctx.projectId());
        FieldGuard guard = FieldGuard.of(workflow, issue.statusId(), ctx.roleIds(), fields.fields(ctx.projectId()));
        record Row(UUID id, String kind, String field, String oldValue, String newValue, UUID actor, Instant at) {
        }
        List<Row> rows = jdbc.sql("""
                SELECT id, kind, field, old_value::text AS old_value, new_value::text AS new_value, actor_id, created_at
                  FROM issue_activity WHERE issue_id = :id ORDER BY created_at, id
                """)
                .param("id", issue.id())
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("field"),
                        rs.getString("old_value"), rs.getString("new_value"), rs.getObject("actor_id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
        Set<UUID> actors = new HashSet<>();
        rows.forEach(r -> {
            if (r.actor() != null) {
                actors.add(r.actor());
            }
        });
        Map<UUID, UserSummary> persons = users.findAll(actors);
        List<IssueActivityEntry> result = new ArrayList<>();
        for (Row row : rows) {
            boolean ruleable = row.field() != null && (row.field().startsWith("cf:") || Workflow.SYSTEM_FIELDS.contains(row.field()));
            if (ruleable && !guard.visible(row.field())) {
                continue; // never reveal changes to fields hidden from this viewer
            }
            result.add(new IssueActivityEntry(row.id(), row.kind(), row.field(), parse(row.oldValue()), parse(row.newValue()),
                    person(persons, row.actor()), row.at()));
        }
        return result;
    }

    private Object parse(String value) {
        return value == null ? null : json.readValue(value, ANY);
    }

    static IssueSummary.StatusRef statusRef(Workflow workflow, UUID statusId) {
        return workflow.status(statusId).map(s -> new IssueSummary.StatusRef(s.id(), s.name(), s.category().name()))
                .orElse(null);
    }

    private static IssueSummary.Person person(Map<UUID, UserSummary> persons, UUID id) {
        if (id == null) {
            return null;
        }
        UserSummary user = persons.get(id);
        return user == null ? new IssueSummary.Person(id, "Unknown user", null)
                : new IssueSummary.Person(id, user.displayName(), user.handle());
    }
}
