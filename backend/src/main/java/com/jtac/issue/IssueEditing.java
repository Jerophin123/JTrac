package com.jtac.issue;

import com.jtac.organization.OrgContext;
import java.util.Map;
import java.util.UUID;

/**
 * Issue writes for other modules, with exactly the checks of the REST API (project
 * permissions, workflow transitions for the caller's roles, field rules, optimistic locking).
 */
public interface IssueEditing {

    /**
     * Applies a partial update; see the REST {@code PATCH} for the accepted keys. A status
     * change is a workflow transition and must be allowed for the caller's roles.
     *
     * @throws org.springframework.dao.OptimisticLockingFailureException if {@code version} is stale
     */
    IssueView update(OrgContext org, String issueKey, Map<String, Object> changes, long version);

    /** Links {@code issueKey} to {@code targetKey} ({@code BLOCKS}, {@code RELATES} or {@code DUPLICATES}). */
    IssueView link(OrgContext org, String issueKey, String targetKey, String type);

    void unlink(OrgContext org, String issueKey, UUID linkId);
}
