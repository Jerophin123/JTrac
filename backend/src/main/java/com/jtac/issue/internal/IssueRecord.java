package com.jtac.issue.internal;

import com.jtac.issue.Priority;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A persisted issue row (all values, before visibility filtering). */
record IssueRecord(UUID id, UUID organizationId, UUID projectId, String projectKey, long number, UUID typeId, String title,
        String description, UUID statusId, Priority priority, UUID assigneeId, UUID reporterId, UUID parentId,
        UUID milestoneId, LocalDate startDate, LocalDate dueDate, BigDecimal estimate, long rank,
        Map<String, Object> customValues, List<UUID> labelIds, Instant createdAt, Instant updatedAt, Instant resolvedAt,
        long version) {

    String key() {
        return projectKey + "-" + number;
    }
}
