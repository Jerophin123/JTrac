package com.jtac.issue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Domain event for every change to an issue (published inside the changing transaction and
 * delivered to {@code @ApplicationModuleListener}s after commit, with at-least-once semantics
 * through the event publication registry).
 *
 * @param kind    CREATED, UPDATED, TRANSITIONED, DELETED, COMMENTED, LINKED, ATTACHMENT_ADDED
 * @param fields  changed field keys (system fields or {@code cf:<key>})
 * @param details small, non-sensitive extras (e.g. {@code assigneeId}, {@code commentId},
 *                {@code mentionedUserIds}); consumers must re-check visibility per recipient
 */
public record IssueChanged(UUID eventId, UUID organizationId, UUID projectId, UUID issueId, String issueKey, String title,
        UUID actorId, String kind, List<String> fields, Map<String, Object> details, Instant occurredAt) {
}
