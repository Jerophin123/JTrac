package com.jtac.issue;

import com.jtac.project.ProjectContext;
import java.util.UUID;

/** An issue the caller is allowed to see, with the caller's project context. */
public record IssueHandle(UUID issueId, String key, String title, UUID statusId, UUID reporterId, UUID assigneeId,
        ProjectContext project) {

    public UUID organizationId() {
        return project.organizationId();
    }
}
