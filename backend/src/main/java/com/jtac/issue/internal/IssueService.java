package com.jtac.issue.internal;

import com.jtac.field.FieldCatalog;
import com.jtac.field.FieldDefinition;
import com.jtac.issue.IssueChanged;
import com.jtac.issue.IssueCommands;
import com.jtac.issue.IssueHandle;
import com.jtac.issue.NewIssue;
import com.jtac.issue.Priority;
import com.jtac.project.ProjectAccess;
import com.jtac.project.ProjectCatalog;
import com.jtac.project.ProjectContext;
import com.jtac.project.ProjectPermission;
import com.jtac.shared.Ids;
import com.jtac.shared.web.ApiErrors;
import com.jtac.workflow.Status;
import com.jtac.workflow.Workflow;
import com.jtac.workflow.WorkflowCatalog;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Issue write operations. Every operation checks project permissions, the published
 * workflow (transitions, field rules) and optimistic locking, records attributed activity,
 * and publishes {@link IssueChanged}.
 */
@Service
class IssueService implements IssueCommands {

    static final int MAX_LABELS = 20;
    static final long RANK_GAP = 1024;

    private final JdbcClient jdbc;
    private final IssueStore store;
    private final ProjectAccess access;
    private final ProjectCatalog catalog;
    private final WorkflowCatalog workflows;
    private final FieldCatalog fields;
    private final ActivityWriter activity;
    private final ApplicationEventPublisher events;
    private final JsonMapper json;

    IssueService(JdbcClient jdbc, IssueStore store, ProjectAccess access, ProjectCatalog catalog, WorkflowCatalog workflows,
            FieldCatalog fields, ActivityWriter activity, ApplicationEventPublisher events, JsonMapper json) {
        this.jdbc = jdbc;
        this.store = store;
        this.access = access;
        this.catalog = catalog;
        this.workflows = workflows;
        this.fields = fields;
        this.activity = activity;
        this.events = events;
        this.json = json;
    }

    // --- create --------------------------------------------------------------------------

    @Override
    @Transactional
    public IssueHandle create(ProjectContext ctx, NewIssue input) {
        IssueRecord created = createIssue(ctx, input);
        return new IssueHandle(created.id(), created.key(), created.title(), created.statusId(), created.reporterId(),
                created.assigneeId(), ctx);
    }

    @Transactional
    IssueRecord createIssue(ProjectContext ctx, NewIssue input) {
        ctx.require(ProjectPermission.CREATE_ISSUES);
        Workflow workflow = workflows.published(ctx.projectId());
        List<FieldDefinition> defs = fields.fields(ctx.projectId());
        IssueDraft draft = IssueDraft.empty();
        draft.statusId = workflow.initialStatusId();
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("typeId", input.typeId() == null ? defaultType(ctx) : input.typeId().toString());
        patch.put("title", input.title());
        if (input.description() != null) {
            patch.put("description", input.description());
        }
        if (input.priority() != null) {
            patch.put("priority", input.priority().name());
        }
        if (input.assigneeId() != null) {
            patch.put("assigneeId", input.assigneeId().toString());
        }
        if (input.parentKey() != null) {
            patch.put("parentKey", input.parentKey());
        }
        if (input.milestoneId() != null) {
            patch.put("milestoneId", input.milestoneId().toString());
        }
        if (input.startDate() != null) {
            patch.put("startDate", input.startDate().toString());
        }
        if (input.dueDate() != null) {
            patch.put("dueDate", input.dueDate().toString());
        }
        if (input.estimate() != null) {
            patch.put("estimate", input.estimate());
        }
        if (input.labelIds() != null && !input.labelIds().isEmpty()) {
            patch.put("labelIds", input.labelIds().stream().map(UUID::toString).toList());
        }
        if (input.customValues() != null && !input.customValues().isEmpty()) {
            patch.put("customValues", input.customValues());
        }
        apply(ctx, null, draft, patch, defs);
        FieldGuard guard = FieldGuard.of(workflow, draft.statusId, ctx.roleIds(), defs);
        authorize(ctx, guard, draft.changed, true);
        requireFilled(guard, draft, defs);

        long number = jdbc.sql("UPDATE project SET next_issue_number = next_issue_number + 1 WHERE id = :p RETURNING next_issue_number - 1")
                .param("p", ctx.projectId()).query(Long.class).single();
        long rank = jdbc.sql("SELECT coalesce(max(rank), 0) + :gap FROM issue WHERE project_id = :p")
                .param("gap", RANK_GAP).param("p", ctx.projectId()).query(Long.class).single();
        UUID id = Ids.newId();
        Status status = workflow.status(draft.statusId).orElseThrow();
        jdbc.sql("""
                INSERT INTO issue (id, organization_id, project_id, number, type_id, title, description, status_id, priority,
                                   assignee_id, reporter_id, parent_id, milestone_id, start_date, due_date, estimate, rank,
                                   custom_values, resolved_at)
                VALUES (:id, :org, :p, :number, :type, :title, :description, :status, :priority, :assignee, :reporter, :parent,
                        :milestone, :start, :due, :estimate, :rank, CAST(:custom AS jsonb),
                        CASE WHEN :resolved THEN now() END)
                """)
                .param("id", id).param("org", ctx.organizationId()).param("p", ctx.projectId()).param("number", number)
                .param("type", draft.typeId).param("title", draft.title).param("description", draft.description)
                .param("status", draft.statusId).param("priority", draft.priority.name()).param("assignee", draft.assigneeId)
                .param("reporter", ctx.actorId()).param("parent", draft.parentId).param("milestone", draft.milestoneId)
                .param("start", draft.startDate).param("due", draft.dueDate).param("estimate", draft.estimate)
                .param("rank", rank).param("custom", json.writeValueAsString(draft.customValues))
                .param("resolved", status.category().isResolved()).update();
        replaceLabels(ctx.projectId(), id, draft.labelIds);
        addWatcher(ctx.organizationId(), id, ctx.actorId());
        if (draft.assigneeId != null) {
            addWatcher(ctx.organizationId(), id, draft.assigneeId);
        }
        activity.write(ctx.organizationId(), id, ctx.actorId(), "CREATED", null, null, null);
        IssueRecord created = store.byId(ctx.organizationId(), id).orElseThrow();
        Map<String, Object> details = new HashMap<>();
        if (draft.assigneeId != null) {
            details.put("assigneeId", draft.assigneeId.toString());
        }
        publish(ctx, created, "CREATED", List.copyOf(draft.changed), details);
        return created;
    }

    // --- update / transition -------------------------------------------------------------

    /**
     * Applies a partial update. {@code patch} keys: title, description, typeId, statusId,
     * priority, assigneeId, parentKey, milestoneId, startDate, dueDate, estimate, labelIds,
     * customValues (partial map). A JSON null clears a field.
     */
    @Transactional
    IssueRecord update(ProjectContext ctx, IssueRecord current, Map<String, Object> patch, long expectedVersion) {
        IssueRecord locked = store.lock(ctx.organizationId(), current.id()).orElseThrow(() -> ApiErrors.notFound("Issue"));
        if (locked.version() != expectedVersion) {
            throw new OptimisticLockingFailureException("issue changed");
        }
        Workflow workflow = workflows.published(ctx.projectId());
        List<FieldDefinition> defs = fields.fields(ctx.projectId());
        IssueDraft draft = IssueDraft.of(locked);
        apply(ctx, locked, draft, patch, defs);
        if (draft.changed.isEmpty()) {
            return locked;
        }
        FieldGuard currentGuard = FieldGuard.of(workflow, locked.statusId(), ctx.roleIds(), defs);
        authorize(ctx, currentGuard, draft.changed, false);
        if (draft.changed.contains("status")) {
            if (!workflow.allows(locked.statusId(), draft.statusId, ctx.roleIds())) {
                throw ApiErrors.forbidden("This status change is not allowed by the workflow for your role.");
            }
        }
        FieldGuard targetGuard = FieldGuard.of(workflow, draft.statusId, ctx.roleIds(), defs);
        requireFilled(targetGuard, draft, defs);

        Status from = workflow.status(locked.statusId()).orElseThrow();
        Status to = workflow.status(draft.statusId).orElseThrow();
        int updated = jdbc.sql("""
                UPDATE issue SET type_id = :type, title = :title, description = :description, status_id = :status,
                       priority = :priority, assignee_id = :assignee, parent_id = :parent, milestone_id = :milestone,
                       start_date = :start, due_date = :due, estimate = :estimate, custom_values = CAST(:custom AS jsonb),
                       resolved_at = CASE WHEN :resolved THEN coalesce(resolved_at, now()) ELSE NULL END,
                       updated_at = now(), version = version + 1
                 WHERE id = :id AND version = :version
                """)
                .param("type", draft.typeId).param("title", draft.title).param("description", draft.description)
                .param("status", draft.statusId).param("priority", draft.priority.name()).param("assignee", draft.assigneeId)
                .param("parent", draft.parentId).param("milestone", draft.milestoneId).param("start", draft.startDate)
                .param("due", draft.dueDate).param("estimate", draft.estimate)
                .param("custom", json.writeValueAsString(draft.customValues))
                .param("resolved", to.category().isResolved()).param("id", locked.id()).param("version", expectedVersion).update();
        if (updated == 0) {
            throw new OptimisticLockingFailureException("issue changed");
        }
        if (draft.changed.contains("labels")) {
            replaceLabels(ctx.projectId(), locked.id(), draft.labelIds);
        }
        if (draft.changed.contains("assignee") && draft.assigneeId != null) {
            addWatcher(ctx.organizationId(), locked.id(), draft.assigneeId);
        }
        IssueRecord saved = store.byId(ctx.organizationId(), locked.id()).orElseThrow();
        activity.writeChanges(ctx, locked, saved, draft.changed, defs, workflow);
        String kind = draft.changed.contains("status") ? "TRANSITIONED" : "UPDATED";
        Map<String, Object> details = new HashMap<>();
        if (draft.changed.contains("assignee") && draft.assigneeId != null) {
            details.put("assigneeId", draft.assigneeId.toString());
        }
        if (draft.changed.contains("status")) {
            details.put("fromStatus", from.name());
            details.put("toStatus", to.name());
        }
        publish(ctx, saved, kind, List.copyOf(draft.changed), details);
        return saved;
    }

    @Transactional
    void delete(ProjectContext ctx, IssueRecord issue) {
        ctx.require(ProjectPermission.DELETE_ISSUES);
        jdbc.sql("DELETE FROM issue WHERE id = :id AND organization_id = :org").param("id", issue.id())
                .param("org", ctx.organizationId()).update();
        publish(ctx, issue, "DELETED", List.of(), Map.of());
    }

    // --- board ordering ------------------------------------------------------------------

    /**
     * Moves an issue on the board: optionally to another status (a workflow transition), and
     * between two neighbours ({@code afterId} above it, {@code beforeId} below it).
     */
    @Transactional
    IssueRecord move(ProjectContext ctx, IssueRecord issue, UUID statusId, UUID afterId, UUID beforeId, long expectedVersion) {
        IssueRecord current = issue;
        if (statusId != null && !statusId.equals(issue.statusId())) {
            Map<String, Object> patch = new HashMap<>();
            patch.put("statusId", statusId.toString());
            current = update(ctx, issue, patch, expectedVersion);
        } else {
            ctx.require(ProjectPermission.EDIT_ISSUES);
            if (issue.version() != expectedVersion) {
                throw new OptimisticLockingFailureException("issue changed");
            }
        }
        if (afterId == null && beforeId == null) {
            return current;
        }
        jdbc.sql("SELECT id FROM project WHERE id = :p FOR UPDATE").param("p", ctx.projectId()).query(UUID.class).single();
        Long newRank = rankBetween(ctx.projectId(), afterId, beforeId);
        if (newRank == null) {
            rebalance(ctx.projectId());
            newRank = rankBetween(ctx.projectId(), afterId, beforeId);
        }
        jdbc.sql("UPDATE issue SET rank = :rank WHERE id = :id").param("rank", newRank).param("id", current.id()).update();
        return store.byId(ctx.organizationId(), current.id()).orElseThrow();
    }

    private Long rankBetween(UUID projectId, UUID afterId, UUID beforeId) {
        Long after = afterId == null ? null : rankOf(projectId, afterId);
        Long before = beforeId == null ? null : rankOf(projectId, beforeId);
        if (after == null && before == null) {
            throw ApiErrors.invalid("afterId", "Unknown neighbour issue.");
        }
        if (after == null) {
            return before - RANK_GAP;
        }
        if (before == null) {
            return after + RANK_GAP;
        }
        long low = Math.min(after, before);
        long high = Math.max(after, before);
        if (high - low < 2) {
            return null;
        }
        return low + (high - low) / 2;
    }

    private Long rankOf(UUID projectId, UUID issueId) {
        return jdbc.sql("SELECT rank FROM issue WHERE id = :id AND project_id = :p").param("id", issueId).param("p", projectId)
                .query(Long.class).optional().orElseThrow(() -> ApiErrors.invalid("afterId", "Unknown neighbour issue."));
    }

    /** Re-spaces ranks of a project (order preserved). Ranks are not versioned data. */
    private void rebalance(UUID projectId) {
        jdbc.sql("""
                UPDATE issue SET rank = s.rn * :gap
                  FROM (SELECT id, row_number() OVER (ORDER BY rank, id) AS rn FROM issue WHERE project_id = :p) s
                 WHERE issue.id = s.id
                """)
                .param("gap", RANK_GAP).param("p", projectId).update();
    }

    // --- links and watchers --------------------------------------------------------------

    @Transactional
    UUID addLink(ProjectContext ctx, IssueRecord source, IssueRecord target, String type) {
        ctx.require(ProjectPermission.EDIT_ISSUES);
        if (!Set.of("BLOCKS", "RELATES", "DUPLICATES").contains(type)) {
            throw ApiErrors.invalid("type", "Use BLOCKS, RELATES or DUPLICATES.");
        }
        if (source.id().equals(target.id())) {
            throw ApiErrors.invalid("targetKey", "An issue cannot be linked to itself.");
        }
        if ("BLOCKS".equals(type) && blocksPath(target.id(), source.id())) {
            throw ApiErrors.invalid("targetKey", "This would create a circular dependency.");
        }
        UUID id = Ids.newId();
        int inserted = jdbc.sql("""
                INSERT INTO issue_link (id, organization_id, source_id, target_id, type, created_by)
                VALUES (:id, :org, :source, :target, :type, :by) ON CONFLICT DO NOTHING
                """)
                .param("id", id).param("org", ctx.organizationId()).param("source", source.id()).param("target", target.id())
                .param("type", type).param("by", ctx.actorId()).update();
        if (inserted == 0) {
            throw ApiErrors.conflict("These issues are already linked this way.");
        }
        activity.write(ctx.organizationId(), source.id(), ctx.actorId(), "LINK_ADDED", "links", null,
                Map.of("type", type, "key", target.key()));
        publish(ctx, source, "LINKED", List.of("links"), Map.of("targetKey", target.key(), "type", type));
        return id;
    }

    /** Whether {@code from} (transitively) blocks {@code to}. */
    private boolean blocksPath(UUID from, UUID to) {
        return jdbc.sql("""
                WITH RECURSIVE chain(id, depth) AS (
                    SELECT target_id, 1 FROM issue_link WHERE source_id = :from AND type = 'BLOCKS'
                    UNION
                    SELECT l.target_id, c.depth + 1 FROM issue_link l JOIN chain c ON l.source_id = c.id
                     WHERE l.type = 'BLOCKS' AND c.depth < 50)
                SELECT count(*) FROM chain WHERE id = :to
                """)
                .param("from", from).param("to", to).query(Long.class).single() > 0;
    }

    @Transactional
    void removeLink(ProjectContext ctx, IssueRecord issue, UUID linkId) {
        ctx.require(ProjectPermission.EDIT_ISSUES);
        int deleted = jdbc.sql("DELETE FROM issue_link WHERE id = :id AND (source_id = :issue OR target_id = :issue)")
                .param("id", linkId).param("issue", issue.id()).update();
        if (deleted == 0) {
            throw ApiErrors.notFound("Link");
        }
        activity.write(ctx.organizationId(), issue.id(), ctx.actorId(), "LINK_REMOVED", "links", null, null);
    }

    void addWatcher(UUID organizationId, UUID issueId, UUID userId) {
        jdbc.sql("""
                INSERT INTO issue_watcher (issue_id, organization_id, user_id)
                SELECT :issue, :org, :user WHERE EXISTS (SELECT 1 FROM membership WHERE organization_id = :org AND user_id = :user)
                ON CONFLICT DO NOTHING
                """)
                .param("issue", issueId).param("org", organizationId).param("user", userId).update();
    }

    @Transactional
    void setWatching(ProjectContext ctx, IssueRecord issue, boolean watching) {
        if (watching) {
            addWatcher(ctx.organizationId(), issue.id(), ctx.actorId());
        } else {
            jdbc.sql("DELETE FROM issue_watcher WHERE issue_id = :issue AND user_id = :user")
                    .param("issue", issue.id()).param("user", ctx.actorId()).update();
        }
    }

    // --- applying and validating changes -------------------------------------------------

    private void apply(ProjectContext ctx, IssueRecord current, IssueDraft draft, Map<String, Object> patch,
            List<FieldDefinition> defs) {
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            Object value = entry.getValue();
            switch (entry.getKey()) {
                case "title" -> {
                    String title = value == null ? "" : value.toString().strip();
                    if (title.isEmpty() || title.length() > 255) {
                        throw ApiErrors.invalid("title", "Use 1 to 255 characters.");
                    }
                    set(draft, "title", draft.title, title, v -> draft.title = v);
                }
                case "description" -> {
                    String description = value == null || value.toString().isBlank() ? null : value.toString();
                    if (description != null && description.length() > 100_000) {
                        throw ApiErrors.invalid("description", "At most 100,000 characters.");
                    }
                    set(draft, "description", draft.description, description, v -> draft.description = v);
                }
                case "typeId" -> {
                    UUID typeId = uuid("typeId", value);
                    if (typeId == null) {
                        throw ApiErrors.invalid("typeId", "Choose an issue type.");
                    }
                    if (!typeId.equals(draft.typeId)) {
                        ProjectCatalog.IssueType type = catalog.issueTypes(ctx.projectId()).stream()
                                .filter(t -> t.id().equals(typeId) && !t.archived()).findFirst()
                                .orElseThrow(() -> ApiErrors.invalid("typeId", "Unknown or archived issue type."));
                        set(draft, "type", draft.typeId, type.id(), v -> draft.typeId = v);
                    }
                }
                case "statusId" -> {
                    UUID statusId = uuid("statusId", value);
                    if (statusId == null) {
                        throw ApiErrors.invalid("statusId", "Choose a status.");
                    }
                    if (current == null) {
                        throw ApiErrors.invalid("statusId", "New issues start in the workflow's initial status.");
                    }
                    if (workflows.published(ctx.projectId()).boardStatuses().stream().noneMatch(s -> s.id().equals(statusId))) {
                        throw ApiErrors.invalid("statusId", "Unknown status.");
                    }
                    set(draft, "status", draft.statusId, statusId, v -> draft.statusId = v);
                }
                case "priority" -> {
                    Priority priority;
                    try {
                        priority = value == null ? Priority.NONE : Priority.valueOf(value.toString());
                    } catch (IllegalArgumentException e) {
                        throw ApiErrors.invalid("priority", "Use URGENT, HIGH, MEDIUM, LOW or NONE.");
                    }
                    set(draft, "priority", draft.priority, priority, v -> draft.priority = v);
                }
                case "assigneeId" -> {
                    UUID assignee = uuid("assigneeId", value);
                    if (assignee != null && !assignee.equals(draft.assigneeId) && !canBrowse(ctx, assignee)) {
                        throw ApiErrors.invalid("assigneeId", "This person cannot access the project.");
                    }
                    set(draft, "assignee", draft.assigneeId, assignee, v -> draft.assigneeId = v);
                }
                case "parentKey" -> {
                    UUID parentId = null;
                    if (value != null && !value.toString().isBlank()) {
                        IssueRecord parent = store.byKey(ctx.organizationId(), value.toString())
                                .filter(p -> p.projectId().equals(ctx.projectId()))
                                .orElseThrow(() -> ApiErrors.invalid("parentKey", "The parent must be an issue of this project."));
                        if (current != null && createsCycle(parent, current.id())) {
                            throw ApiErrors.invalid("parentKey", "An issue cannot be its own ancestor.");
                        }
                        parentId = parent.id();
                    }
                    set(draft, "parent", draft.parentId, parentId, v -> draft.parentId = v);
                }
                case "milestoneId" -> {
                    UUID milestone = uuid("milestoneId", value);
                    if (milestone != null && catalog.milestones(ctx.projectId()).stream().noneMatch(m -> m.id().equals(milestone))) {
                        throw ApiErrors.invalid("milestoneId", "Unknown milestone.");
                    }
                    set(draft, "milestone", draft.milestoneId, milestone, v -> draft.milestoneId = v);
                }
                case "startDate" -> set(draft, "startDate", draft.startDate, date("startDate", value), v -> draft.startDate = v);
                case "dueDate" -> set(draft, "dueDate", draft.dueDate, date("dueDate", value), v -> draft.dueDate = v);
                case "estimate" -> {
                    BigDecimal estimate = null;
                    if (value != null && !value.toString().isBlank()) {
                        try {
                            estimate = new BigDecimal(value.toString()).stripTrailingZeros();
                        } catch (NumberFormatException e) {
                            throw ApiErrors.invalid("estimate", "Enter a number.");
                        }
                        if (estimate.signum() < 0 || estimate.compareTo(new BigDecimal("1000000")) > 0 || estimate.scale() > 2) {
                            throw ApiErrors.invalid("estimate", "Use a number from 0 to 1,000,000 with at most 2 decimals.");
                        }
                    }
                    BigDecimal old = draft.estimate;
                    if (!(old == null ? estimate == null : estimate != null && old.compareTo(estimate) == 0)) {
                        draft.estimate = estimate;
                        draft.changed.add("estimate");
                    }
                }
                case "labelIds" -> {
                    List<UUID> labels = new ArrayList<>(new LinkedHashSet<>(uuidList("labelIds", value)));
                    if (labels.size() > MAX_LABELS) {
                        throw ApiErrors.invalid("labelIds", "At most " + MAX_LABELS + " labels.");
                    }
                    Set<UUID> known = catalog.labels(ctx.projectId()).stream().map(ProjectCatalog.Label::id).collect(Collectors.toSet());
                    if (!known.containsAll(labels)) {
                        throw ApiErrors.invalid("labelIds", "Unknown label.");
                    }
                    if (!new HashSet<>(labels).equals(new HashSet<>(draft.labelIds))) {
                        draft.labelIds = labels;
                        draft.changed.add("labels");
                    }
                }
                case "customValues" -> {
                    if (!(value instanceof Map<?, ?> values)) {
                        throw ApiErrors.invalid("customValues", "Use an object of field keys to values.");
                    }
                    Predicate<UUID> userAllowed = user -> canBrowse(ctx, user);
                    for (Map.Entry<?, ?> custom : values.entrySet()) {
                        String key = String.valueOf(custom.getKey());
                        FieldDefinition def = defs.stream().filter(f -> f.key().equals(key)).findFirst()
                                .orElseThrow(() -> ApiErrors.invalid("customValues." + key, "Unknown field."));
                        Object normalized = fields.normalize(def, custom.getValue(), userAllowed);
                        Object old = draft.customValues.get(key);
                        if (!Objects.equals(comparable(old), comparable(normalized))) {
                            if (normalized == null) {
                                draft.customValues.remove(key);
                            } else {
                                draft.customValues.put(key, normalized instanceof BigDecimal n ? n : normalized);
                            }
                            draft.changed.add(def.ruleKey());
                        }
                    }
                }
                default -> throw ApiErrors.invalid(entry.getKey(), "Unknown field.");
            }
        }
        if (draft.startDate != null && draft.dueDate != null && draft.startDate.isAfter(draft.dueDate)) {
            throw ApiErrors.invalid("dueDate", "The due date must not be before the start date.");
        }
        if (draft.parentId == null && current == null && isSubtaskType(ctx, draft.typeId)) {
            throw ApiErrors.invalid("parentKey", "Sub-tasks need a parent issue.");
        }
    }

    private static Object comparable(Object value) {
        if (value instanceof Number n) {
            return new BigDecimal(n.toString()).stripTrailingZeros();
        }
        return value;
    }

    private boolean isSubtaskType(ProjectContext ctx, UUID typeId) {
        return catalog.issueTypes(ctx.projectId()).stream().anyMatch(t -> t.id().equals(typeId) && t.subtask());
    }

    private boolean createsCycle(IssueRecord parent, UUID issueId) {
        UUID cursor = parent.id();
        for (int depth = 0; cursor != null && depth < 20; depth++) {
            if (cursor.equals(issueId)) {
                return true;
            }
            cursor = jdbc.sql("SELECT parent_id FROM issue WHERE id = :id").param("id", cursor).query(UUID.class).optional().orElse(null);
        }
        return false;
    }

    /** Checks permissions and field rules for the changed fields. */
    private static void authorize(ProjectContext ctx, FieldGuard guard, Collection<String> changed, boolean creating) {
        for (String field : changed) {
            switch (field) {
                case "status" -> ctx.require(ProjectPermission.TRANSITION_ISSUES);
                case "assignee" -> ctx.require(ProjectPermission.ASSIGN_ISSUES);
                default -> {
                    if (!creating) {
                        ctx.require(ProjectPermission.EDIT_ISSUES);
                    }
                }
            }
            if ((field.startsWith("cf:") || com.jtac.workflow.Workflow.SYSTEM_FIELDS.contains(field)) && !guard.editable(field)) {
                throw ApiErrors.invalid(fieldPath(field), "This field cannot be changed in the current status for your role.");
            }
        }
    }

    /** Required fields of the (target) status must be filled. */
    private static void requireFilled(FieldGuard guard, IssueDraft draft, List<FieldDefinition> defs) {
        List<ApiErrors.FieldError> errors = new ArrayList<>();
        Map<String, Object> system = new HashMap<>();
        system.put("description", draft.description);
        system.put("assignee", draft.assigneeId);
        system.put("priority", draft.priority == Priority.NONE ? null : draft.priority);
        system.put("labels", draft.labelIds.isEmpty() ? null : draft.labelIds);
        system.put("dueDate", draft.dueDate);
        system.put("startDate", draft.startDate);
        system.put("estimate", draft.estimate);
        system.put("milestone", draft.milestoneId);
        system.put("parent", draft.parentId);
        system.forEach((field, value) -> {
            if (guard.required(field) && FieldCatalog.isEmpty(value)) {
                errors.add(new ApiErrors.FieldError(fieldPath(field), "Required in this status."));
            }
        });
        for (FieldDefinition def : defs) {
            if (guard.required(def.ruleKey()) && FieldCatalog.isEmpty(draft.customValues.get(def.key()))) {
                errors.add(new ApiErrors.FieldError("customValues." + def.key(), def.name() + " is required in this status."));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiErrors.ValidationFailed(errors);
        }
    }

    static String fieldPath(String field) {
        return switch (field) {
            case "assignee" -> "assigneeId";
            case "labels" -> "labelIds";
            case "milestone" -> "milestoneId";
            case "parent" -> "parentKey";
            case "type" -> "typeId";
            case "status" -> "statusId";
            default -> field.startsWith("cf:") ? "customValues." + field.substring(3) : field;
        };
    }

    private boolean canBrowse(ProjectContext ctx, UUID userId) {
        return access.grantsFor(ctx.organizationId(), ctx.projectId(), userId).isPresent();
    }

    private String defaultType(ProjectContext ctx) {
        return catalog.issueTypes(ctx.projectId()).stream().filter(t -> !t.archived() && !t.subtask()).findFirst()
                .map(t -> t.id().toString()).orElseThrow(() -> ApiErrors.conflict("The project has no active issue type."));
    }

    private void replaceLabels(UUID projectId, UUID issueId, List<UUID> labels) {
        jdbc.sql("DELETE FROM issue_label WHERE issue_id = :id").param("id", issueId).update();
        for (UUID label : labels) {
            jdbc.sql("INSERT INTO issue_label (issue_id, project_id, label_id) VALUES (:id, :p, :label)")
                    .param("id", issueId).param("p", projectId).param("label", label).update();
        }
    }

    private void publish(ProjectContext ctx, IssueRecord issue, String kind, List<String> changed, Map<String, Object> details) {
        events.publishEvent(new IssueChanged(Ids.newId(), ctx.organizationId(), ctx.projectId(), issue.id(), issue.key(),
                issue.title(), ctx.actorId(), kind, changed, Map.copyOf(details), Instant.now()));
    }

    private static <T> void set(IssueDraft draft, String field, T old, T value, java.util.function.Consumer<T> setter) {
        if (!Objects.equals(old, value)) {
            setter.accept(value);
            draft.changed.add(field);
        }
    }

    private static UUID uuid(String field, Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            throw ApiErrors.invalid(field, "Invalid identifier.");
        }
    }

    private static List<UUID> uuidList(String field, Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof Collection<?> values)) {
            throw ApiErrors.invalid(field, "Use a list.");
        }
        List<UUID> result = new ArrayList<>();
        for (Object item : values) {
            result.add(uuid(field, item));
        }
        return result;
    }

    private static LocalDate date(String field, Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.toString());
        } catch (DateTimeParseException e) {
            throw ApiErrors.invalid(field, "Use a date in YYYY-MM-DD format.");
        }
    }

    Optional<IssueRecord> findVisible(ProjectContext ctx, String key) {
        return store.byKey(ctx.organizationId(), key).filter(i -> i.projectId().equals(ctx.projectId()));
    }
}
