package com.jtac.realtime.internal;

import com.jtac.issue.IssueChanged;
import com.jtac.project.ProjectAccess;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * After an issue change commits, tells everyone who can browse the project that the issue
 * changed (no field data), so boards and lists refresh.
 */
@Component
class IssueBroadcaster {

    private final SseHub hub;
    private final ProjectAccess projects;

    IssueBroadcaster(SseHub hub, ProjectAccess projects) {
        this.hub = hub;
        this.projects = projects;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(IssueChanged event) {
        hub.send(event.organizationId(), projects.browsers(event.organizationId(), event.projectId()), "issue.changed",
                Map.of("issueKey", event.issueKey(), "projectId", event.projectId().toString(), "kind", event.kind(),
                        "actorId", event.actorId() == null ? "" : event.actorId().toString()));
    }
}
