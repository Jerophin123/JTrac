package com.jtac.issue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Values for a new issue. Only {@code title} is required; the type defaults to the project's
 * first non-subtask type and the status to the workflow's initial status. Field rules of
 * that status apply (hidden and read-only fields cannot be set; required ones must be).
 */
public record NewIssue(UUID typeId, String title, String description, Priority priority, UUID assigneeId, String parentKey,
        UUID milestoneId, LocalDate startDate, LocalDate dueDate, BigDecimal estimate, List<UUID> labelIds,
        Map<String, Object> customValues) {
}
