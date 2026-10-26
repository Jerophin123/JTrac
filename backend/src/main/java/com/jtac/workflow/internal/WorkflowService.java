package com.jtac.workflow.internal;

import com.jtac.audit.AuditEntry;
import com.jtac.audit.AuditLog;
import com.jtac.field.FieldCatalog;
import com.jtac.field.FieldDefinition;
import com.jtac.project.ProjectCatalog;
import com.jtac.project.ProjectContext;
import com.jtac.project.ProjectCreated;
import com.jtac.project.ProjectPermission;
import com.jtac.shared.Ids;
import com.jtac.shared.web.ApiErrors;
import com.jtac.workflow.FieldAccess;
import com.jtac.workflow.Status;
import com.jtac.workflow.StatusCategory;
import com.jtac.workflow.StatusUsage;
import com.jtac.workflow.Workflow;
import com.jtac.workflow.WorkflowCatalog;
import com.jtac.workflow.WorkflowDefinition;
import com.jtac.workflow.WorkflowPublished;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
class WorkflowService implements WorkflowCatalog {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final ProjectCatalog projects;
    private final FieldCatalog fields;
    private final StatusUsage usage;
    private final ApplicationEventPublisher events;
    private final AuditLog audit;

    WorkflowService(JdbcClient jdbc, JsonMapper json, ProjectCatalog projects, FieldCatalog fields, StatusUsage usage,
            ApplicationEventPublisher events, AuditLog audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.projects = projects;
        this.fields = fields;
        this.usage = usage;
        this.events = events;
        this.audit = audit;
    }

    record VersionInfo(UUID id, int versionNo, String state, WorkflowDefinition definition, OffsetDateTime publishedAt,
            String publishedBy) {
    }

    record Overview(VersionInfo published, VersionInfo draft, List<Status> statuses, Map<UUID, Long> issueCounts) {
    }

    record Impact(List<RemovedStatus> removedStatuses) {
    }

    record RemovedStatus(UUID statusId, String name, long issueCount) {
    }

    // --- WorkflowCatalog -----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Workflow published(UUID projectId) {
        VersionInfo version = version(projectId, "PUBLISHED")
                .orElseThrow(() -> new IllegalStateException("Project " + projectId + " has no published workflow"));
        Map<UUID, Status> byId = new LinkedHashMap<>();
        statuses(projectId).forEach(s -> byId.put(s.id(), s));
        return new Workflow(version.id(), version.versionNo(), version.definition(), byId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Status> statuses(UUID projectId) {
        return jdbc.sql("""
                SELECT id, name, category, position, retired_at IS NOT NULL AS retired FROM status
                 WHERE project_id = :p ORDER BY retired_at NULLS FIRST, position, lower(name)
                """)
                .param("p", projectId)
                .query((rs, n) -> new Status(rs.getObject("id", UUID.class), rs.getString("name"),
                        StatusCategory.valueOf(rs.getString("category")), rs.getInt("position"), rs.getBoolean("retired")))
                .list();
    }

    // --- provisioning --------------------------------------------------------------------

    /** Creates the default statuses and publishes version 1 when a project is created (same transaction). */
    @EventListener
    void onProjectCreated(ProjectCreated event) {
        UUID adminRole = projects.adminRoleId(event.projectId()).orElseThrow();
        WorkflowDefinition definition = "STRUCTURED".equals(event.template())
                ? structured(event, adminRole) : kanban(event);
        insertVersion(event.organizationId(), event.projectId(), 1, "PUBLISHED", definition, event.actorId(), true);
    }

    private WorkflowDefinition kanban(ProjectCreated event) {
        List<UUID> ids = new ArrayList<>();
        String[][] specs = {{"Backlog", "TODO"}, {"Todo", "TODO"}, {"In Progress", "IN_PROGRESS"},
                {"In Review", "IN_PROGRESS"}, {"Done", "DONE"}, {"Canceled", "CANCELED"}};
        for (int i = 0; i < specs.length; i++) {
            ids.add(insertStatus(event.organizationId(), event.projectId(), specs[i][0], StatusCategory.valueOf(specs[i][1]), i));
        }
        List<WorkflowDefinition.Transition> transitions = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            transitions.add(new WorkflowDefinition.Transition("to-" + i, "Move to " + specs[i][0], List.of(), ids.get(i), List.of()));
        }
        return new WorkflowDefinition(ids.get(1), ids, transitions, List.of());
    }

    private WorkflowDefinition structured(ProjectCreated event, UUID adminRole) {
        UUID open = insertStatus(event.organizationId(), event.projectId(), "To Do", StatusCategory.TODO, 0);
        UUID progress = insertStatus(event.organizationId(), event.projectId(), "In Progress", StatusCategory.IN_PROGRESS, 1);
        UUID review = insertStatus(event.organizationId(), event.projectId(), "In Review", StatusCategory.IN_PROGRESS, 2);
        UUID done = insertStatus(event.organizationId(), event.projectId(), "Done", StatusCategory.DONE, 3);
        UUID canceled = insertStatus(event.organizationId(), event.projectId(), "Canceled", StatusCategory.CANCELED, 4);
        List<WorkflowDefinition.Transition> transitions = List.of(
                new WorkflowDefinition.Transition("start", "Start progress", List.of(open), progress, List.of()),
                new WorkflowDefinition.Transition("submit", "Submit for review", List.of(progress), review, List.of()),
                new WorkflowDefinition.Transition("rework", "Request changes", List.of(review), progress, List.of()),
                new WorkflowDefinition.Transition("approve", "Approve", List.of(review), done, List.of(adminRole)),
                new WorkflowDefinition.Transition("reopen", "Reopen", List.of(done, canceled), open, List.of()),
                new WorkflowDefinition.Transition("cancel", "Cancel", List.of(open, progress, review), canceled, List.of()));
        List<WorkflowDefinition.Rule> rules = List.of(
                new WorkflowDefinition.Rule(progress, null, "assignee", FieldAccess.REQUIRED),
                new WorkflowDefinition.Rule(review, null, "assignee", FieldAccess.REQUIRED));
        return new WorkflowDefinition(open, List.of(open, progress, review, done, canceled), transitions, rules);
    }

    // --- administration ------------------------------------------------------------------

    @Transactional(readOnly = true)
    Overview overview(ProjectContext ctx) {
        return new Overview(version(ctx.projectId(), "PUBLISHED").orElseThrow(), version(ctx.projectId(), "DRAFT").orElse(null),
                statuses(ctx.projectId()), usage.issueCountsByStatus(ctx.projectId()));
    }

    @Transactional
    Status createStatus(ProjectContext ctx, String name, StatusCategory category) {
        manage(ctx);
        if (category == null) {
            throw ApiErrors.invalid("category", "Choose a category.");
        }
        String clean = requireName(name);
        if (statuses(ctx.projectId()).stream().anyMatch(s -> !s.retired() && s.name().equalsIgnoreCase(clean))) {
            throw ApiErrors.invalid("name", "A status with this name already exists.");
        }
        UUID id = insertStatus(ctx.organizationId(), ctx.projectId(), clean, category, 1000);
        return statuses(ctx.projectId()).stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    Status updateStatus(ProjectContext ctx, UUID statusId, String name, StatusCategory category) {
        manage(ctx);
        String clean = requireName(name);
        if (statuses(ctx.projectId()).stream().anyMatch(s -> !s.retired() && !s.id().equals(statusId) && s.name().equalsIgnoreCase(clean))) {
            throw ApiErrors.invalid("name", "A status with this name already exists.");
        }
        int updated = jdbc.sql("UPDATE status SET name = :name, category = :category WHERE id = :id AND project_id = :p")
                .param("name", clean).param("category", category.name()).param("id", statusId).param("p", ctx.projectId()).update();
        if (updated == 0) {
            throw ApiErrors.notFound("Status");
        }
        audit.record(AuditEntry.of(ctx.organizationId(), ctx.actorId(), "workflow.status_updated").target("status", statusId)
                .detail("name", clean).detail("category", category.name()));
        return statuses(ctx.projectId()).stream().filter(s -> s.id().equals(statusId)).findFirst().orElseThrow();
    }

    @Transactional
    VersionInfo saveDraft(ProjectContext ctx, WorkflowDefinition definition) {
        manage(ctx);
        validate(ctx, definition);
        Optional<VersionInfo> draft = version(ctx.projectId(), "DRAFT");
        if (draft.isPresent()) {
            jdbc.sql("UPDATE workflow_version SET definition = CAST(:d AS jsonb), created_by = :by, created_at = now() WHERE id = :id")
                    .param("d", json.writeValueAsString(definition)).param("by", ctx.actorId()).param("id", draft.get().id()).update();
        } else {
            int next = nextVersionNo(ctx.projectId());
            insertVersion(ctx.organizationId(), ctx.projectId(), next, "DRAFT", definition, ctx.actorId(), false);
        }
        return version(ctx.projectId(), "DRAFT").orElseThrow();
    }

    @Transactional
    void discardDraft(ProjectContext ctx) {
        manage(ctx);
        jdbc.sql("DELETE FROM workflow_version WHERE project_id = :p AND state = 'DRAFT'").param("p", ctx.projectId()).update();
    }

    @Transactional(readOnly = true)
    Impact impact(ProjectContext ctx) {
        VersionInfo draft = version(ctx.projectId(), "DRAFT").orElseThrow(() -> ApiErrors.notFound("Draft workflow"));
        return new Impact(removed(ctx, draft.definition()));
    }

    /**
     * Publishes the draft. Issues in statuses that the new version no longer uses must be
     * mapped to statuses of the new version; they are migrated in this transaction.
     */
    @Transactional
    VersionInfo publish(ProjectContext ctx, Map<UUID, UUID> mapping) {
        manage(ctx);
        jdbc.sql("SELECT id FROM project WHERE id = :p FOR UPDATE").param("p", ctx.projectId()).query(UUID.class).single();
        VersionInfo draft = version(ctx.projectId(), "DRAFT").orElseThrow(() -> ApiErrors.notFound("Draft workflow"));
        validate(ctx, draft.definition());
        Map<UUID, UUID> cleanMapping = new HashMap<>();
        Set<UUID> newStatuses = new HashSet<>(draft.definition().statuses());
        for (RemovedStatus removed : removed(ctx, draft.definition())) {
            UUID target = mapping == null ? null : mapping.get(removed.statusId());
            if (removed.issueCount() > 0) {
                if (target == null || !newStatuses.contains(target)) {
                    throw ApiErrors.invalid("mapping." + removed.statusId(),
                            "Choose where the " + removed.issueCount() + " issue(s) in '" + removed.name() + "' should move.");
                }
                cleanMapping.put(removed.statusId(), target);
            }
        }
        jdbc.sql("UPDATE workflow_version SET state = 'SUPERSEDED' WHERE project_id = :p AND state = 'PUBLISHED'")
                .param("p", ctx.projectId()).update();
        jdbc.sql("UPDATE workflow_version SET state = 'PUBLISHED', published_by = :by, published_at = now() WHERE id = :id")
                .param("by", ctx.actorId()).param("id", draft.id()).update();
        List<UUID> order = draft.definition().statuses();
        for (int i = 0; i < order.size(); i++) {
            jdbc.sql("UPDATE status SET position = :pos, retired_at = NULL WHERE id = :id AND project_id = :p")
                    .param("pos", i).param("id", order.get(i)).param("p", ctx.projectId()).update();
        }
        jdbc.sql("UPDATE status SET retired_at = coalesce(retired_at, now()) WHERE project_id = :p AND NOT (id = ANY (:ids))")
                .param("p", ctx.projectId()).param("ids", order.toArray(UUID[]::new)).update();
        events.publishEvent(new WorkflowPublished(ctx.organizationId(), ctx.projectId(), draft.id(), draft.versionNo(),
                Map.copyOf(cleanMapping), ctx.actorId()));
        audit.record(AuditEntry.of(ctx.organizationId(), ctx.actorId(), "workflow.published").target("project", ctx.projectId())
                .detail("version", draft.versionNo()).detail("migratedStatuses", cleanMapping.size()));
        return version(ctx.projectId(), "PUBLISHED").orElseThrow();
    }

    // --- validation ----------------------------------------------------------------------

    private void validate(ProjectContext ctx, WorkflowDefinition definition) {
        if (definition == null || definition.statuses().isEmpty()) {
            throw ApiErrors.invalid("statuses", "A workflow needs at least one status.");
        }
        Map<UUID, Status> all = statuses(ctx.projectId()).stream().collect(Collectors.toMap(Status::id, s -> s));
        Set<UUID> used = new HashSet<>();
        for (UUID id : definition.statuses()) {
            if (!all.containsKey(id)) {
                throw ApiErrors.invalid("statuses", "Unknown status " + id + ".");
            }
            if (!used.add(id)) {
                throw ApiErrors.invalid("statuses", "Each status may appear only once.");
            }
        }
        if (definition.initialStatusId() == null || !used.contains(definition.initialStatusId())) {
            throw ApiErrors.invalid("initialStatusId", "The initial status must be one of the workflow's statuses.");
        }
        Set<UUID> roleIds = projects.roles(ctx.projectId()).stream().map(ProjectCatalog.Role::id).collect(Collectors.toSet());
        Set<String> transitionIds = new HashSet<>();
        for (WorkflowDefinition.Transition t : definition.transitions()) {
            if (t.id() == null || !t.id().matches("^[a-z0-9-]{1,40}$") || !transitionIds.add(t.id())) {
                throw ApiErrors.invalid("transitions", "Each transition needs a unique id (a-z, 0-9, '-').");
            }
            if (t.name() == null || t.name().isBlank() || t.name().length() > 40) {
                throw ApiErrors.invalid("transitions", "Each transition needs a name of 1 to 40 characters.");
            }
            if (!used.contains(t.to()) || !used.containsAll(t.from())) {
                throw ApiErrors.invalid("transitions", "Transition '" + t.name() + "' uses a status outside the workflow.");
            }
            if (!roleIds.containsAll(t.roles())) {
                throw ApiErrors.invalid("transitions", "Transition '" + t.name() + "' references an unknown role.");
            }
        }
        if (definition.transitions().size() > 200 || definition.rules().size() > 2000) {
            throw ApiErrors.invalid("transitions", "The workflow is too large.");
        }
        Set<String> fieldKeys = new HashSet<>(Workflow.SYSTEM_FIELDS);
        for (FieldDefinition field : fields.fields(ctx.projectId())) {
            fieldKeys.add(field.ruleKey());
        }
        Set<String> ruleKeys = new HashSet<>();
        for (WorkflowDefinition.Rule rule : definition.rules()) {
            if (rule.field() == null || !fieldKeys.contains(rule.field())) {
                throw ApiErrors.invalid("rules", "Unknown field '" + rule.field() + "' in a field rule.");
            }
            if (rule.access() == null) {
                throw ApiErrors.invalid("rules", "Each field rule needs an access level.");
            }
            if (rule.statusId() != null && !used.contains(rule.statusId())) {
                throw ApiErrors.invalid("rules", "A field rule references a status outside the workflow.");
            }
            if (rule.roleId() != null && !roleIds.contains(rule.roleId())) {
                throw ApiErrors.invalid("rules", "A field rule references an unknown role.");
            }
            if (!ruleKeys.add(rule.field() + "|" + rule.statusId() + "|" + rule.roleId())) {
                throw ApiErrors.invalid("rules", "Duplicate field rule for '" + rule.field() + "'.");
            }
        }
    }

    private List<RemovedStatus> removed(ProjectContext ctx, WorkflowDefinition next) {
        Workflow current = published(ctx.projectId());
        Map<UUID, Long> counts = usage.issueCountsByStatus(ctx.projectId());
        Set<UUID> kept = new HashSet<>(next.statuses());
        List<RemovedStatus> result = new ArrayList<>();
        for (Status status : statuses(ctx.projectId())) {
            boolean wasInUse = current.definition().statuses().contains(status.id()) || counts.getOrDefault(status.id(), 0L) > 0;
            if (wasInUse && !kept.contains(status.id())) {
                result.add(new RemovedStatus(status.id(), status.name(), counts.getOrDefault(status.id(), 0L)));
            }
        }
        return result;
    }

    // --- persistence helpers -------------------------------------------------------------

    private Optional<VersionInfo> version(UUID projectId, String state) {
        return jdbc.sql("""
                SELECT v.id, v.version_no, v.state, v.definition::text AS definition, v.published_at, u.display_name
                  FROM workflow_version v LEFT JOIN user_account u ON u.id = v.published_by
                 WHERE v.project_id = :p AND v.state = :state
                """)
                .param("p", projectId).param("state", state)
                .query((rs, n) -> new VersionInfo(rs.getObject("id", UUID.class), rs.getInt("version_no"), rs.getString("state"),
                        json.readValue(rs.getString("definition"), WorkflowDefinition.class),
                        rs.getObject("published_at", OffsetDateTime.class), rs.getString("display_name")))
                .optional();
    }

    private int nextVersionNo(UUID projectId) {
        return jdbc.sql("SELECT coalesce(max(version_no), 0) + 1 FROM workflow_version WHERE project_id = :p")
                .param("p", projectId).query(Integer.class).single();
    }

    private void insertVersion(UUID orgId, UUID projectId, int versionNo, String state, WorkflowDefinition definition,
            UUID actorId, boolean published) {
        jdbc.sql("""
                INSERT INTO workflow_version (id, organization_id, project_id, version_no, state, definition, created_by,
                                              published_by, published_at)
                VALUES (:id, :org, :p, :no, :state, CAST(:d AS jsonb), :by, :publishedBy, CASE WHEN :published THEN now() END)
                """)
                .param("id", Ids.newId()).param("org", orgId).param("p", projectId).param("no", versionNo).param("state", state)
                .param("d", json.writeValueAsString(definition)).param("by", actorId)
                .param("publishedBy", published ? actorId : null).param("published", published).update();
    }

    private UUID insertStatus(UUID orgId, UUID projectId, String name, StatusCategory category, int position) {
        UUID id = Ids.newId();
        jdbc.sql("INSERT INTO status (id, organization_id, project_id, name, category, position) VALUES (:id, :org, :p, :name, :cat, :pos)")
                .param("id", id).param("org", orgId).param("p", projectId).param("name", name)
                .param("cat", category.name()).param("pos", position).update();
        return id;
    }

    private static void manage(ProjectContext ctx) {
        ctx.require(ProjectPermission.MANAGE_PROJECT);
        if (ctx.project().archived()) {
            throw ApiErrors.forbidden("This project is archived and read-only.");
        }
    }

    private static String requireName(String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty() || clean.length() > 40) {
            throw ApiErrors.invalid("name", "Use 1 to 40 characters.");
        }
        return clean;
    }
}
