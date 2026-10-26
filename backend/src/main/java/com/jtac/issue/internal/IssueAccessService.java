package com.jtac.issue.internal;

import com.jtac.field.FieldCatalog;
import com.jtac.issue.IssueAccess;
import com.jtac.issue.IssueHandle;
import com.jtac.organization.OrgContext;
import com.jtac.project.ProjectAccess;
import com.jtac.project.ProjectContext;
import com.jtac.shared.web.ApiErrors;
import com.jtac.workflow.Workflow;
import com.jtac.workflow.WorkflowCatalog;
import com.jtac.workflow.WorkflowPublished;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link IssueAccess} for other modules and migration of issues when a workflow version
 * removes statuses.
 */
@Service
class IssueAccessService implements IssueAccess {

    private final JdbcClient jdbc;
    private final IssueStore store;
    private final ProjectAccess projects;
    private final WorkflowCatalog workflows;
    private final FieldCatalog fields;
    private final ActivityWriter activity;
    private final IssueService issues;

    IssueAccessService(JdbcClient jdbc, IssueStore store, ProjectAccess projects, WorkflowCatalog workflows,
            FieldCatalog fields, ActivityWriter activity, IssueService issues) {
        this.jdbc = jdbc;
        this.store = store;
        this.projects = projects;
        this.workflows = workflows;
        this.fields = fields;
        this.activity = activity;
        this.issues = issues;
    }

    @Override
    @Transactional(readOnly = true)
    public IssueHandle require(OrgContext org, String issueKey) {
        IssueRecord issue = store.byKey(org.organizationId(), issueKey).orElseThrow(() -> ApiErrors.notFound("Issue"));
        ProjectContext ctx = projects.visible(org, List.of(issue.projectId())).get(issue.projectId());
        if (ctx == null) {
            throw ApiErrors.notFound("Issue");
        }
        return new IssueHandle(issue.id(), issue.key(), issue.title(), issue.statusId(), issue.reporterId(),
                issue.assigneeId(), ctx);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Set<String>> visibleFieldsFor(UUID organizationId, UUID issueId, UUID userId) {
        Optional<IssueRecord> issue = store.byId(organizationId, issueId);
        if (issue.isEmpty()) {
            return Optional.empty();
        }
        return projects.grantsFor(organizationId, issue.get().projectId(), userId).map(grants -> {
            Workflow workflow = workflows.published(issue.get().projectId());
            FieldGuard guard = FieldGuard.of(workflow, issue.get().statusId(), grants.roleIds(),
                    fields.fields(issue.get().projectId()));
            return Set.copyOf(guard.access().entrySet().stream().filter(e -> e.getValue().visible()).map(Map.Entry::getKey).toList());
        });
    }

    @Override
    @Transactional
    public void recordActivity(UUID organizationId, UUID issueId, UUID actorId, String kind, Map<String, Object> details) {
        activity.write(organizationId, issueId, actorId, kind, null, null, details);
        jdbc.sql("UPDATE issue SET updated_at = now() WHERE id = :id AND organization_id = :org")
                .param("id", issueId).param("org", organizationId).update();
    }

    @Override
    @Transactional
    public void watch(UUID organizationId, UUID issueId, UUID userId) {
        issues.addWatcher(organizationId, issueId, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> watchers(UUID issueId) {
        return Set.copyOf(jdbc.sql("SELECT user_id FROM issue_watcher WHERE issue_id = :id").param("id", issueId)
                .query(UUID.class).list());
    }

    /** Moves issues out of removed statuses within the publishing transaction (explicit migration). */
    @EventListener
    void onWorkflowPublished(WorkflowPublished event) {
        Workflow workflow = workflows.published(event.projectId());
        for (Map.Entry<UUID, UUID> mapping : event.statusMapping().entrySet()) {
            String from = workflow.status(mapping.getKey()).map(s -> s.name()).orElse("?");
            String to = workflow.status(mapping.getValue()).map(s -> s.name()).orElse("?");
            boolean resolved = workflow.status(mapping.getValue()).map(s -> s.category().isResolved()).orElse(false);
            List<UUID> moved = jdbc.sql("""
                    UPDATE issue SET status_id = :to, updated_at = now(), version = version + 1,
                           resolved_at = CASE WHEN :resolved THEN coalesce(resolved_at, now()) ELSE NULL END
                     WHERE project_id = :p AND status_id = :from
                    RETURNING id
                    """)
                    .param("to", mapping.getValue()).param("resolved", resolved).param("p", event.projectId())
                    .param("from", mapping.getKey()).query(UUID.class).list();
            for (UUID issueId : moved) {
                activity.write(event.organizationId(), issueId, event.actorId(), "TRANSITIONED", "status",
                        Map.of("id", mapping.getKey().toString(), "name", from),
                        Map.of("id", mapping.getValue().toString(), "name", to, "reason",
                                "Workflow version " + event.versionNo() + " published"));
            }
        }
    }
}
