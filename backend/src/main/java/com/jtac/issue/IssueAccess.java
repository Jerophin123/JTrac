package com.jtac.issue;

import com.jtac.organization.OrgContext;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Issue resolution and visibility checks for other modules (collaboration, attachments, notifications). */
public interface IssueAccess {

    /** Resolves an issue key (e.g. {@code WEB-12}) the caller can browse, or throws 404. */
    IssueHandle require(OrgContext org, String issueKey);

    /**
     * What {@code userId} can see of the issue right now: empty if they cannot browse it;
     * otherwise the set of visible ruleable fields (system field keys and {@code cf:<key>}).
     */
    Optional<Set<String>> visibleFieldsFor(UUID organizationId, UUID issueId, UUID userId);

    /** Records an activity entry and bumps the issue's {@code updated_at} (e.g. for comments). */
    void recordActivity(UUID organizationId, UUID issueId, UUID actorId, String kind, Map<String, Object> details);

    /** Adds a watcher (no-op if already watching). The user must be able to browse the issue. */
    void watch(UUID organizationId, UUID issueId, UUID userId);

    /** Watchers of an issue. */
    Set<UUID> watchers(UUID issueId);
}
