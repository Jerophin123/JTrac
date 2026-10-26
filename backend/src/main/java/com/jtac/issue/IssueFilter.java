package com.jtac.issue;

import com.jtac.workflow.StatusCategory;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Structured issue query. Every list is optional (null or empty = no constraint).
 *
 * @param assignees user ids, or the tokens {@code me} and {@code none}
 * @param milestones milestone ids, or {@code none}
 * @param text      full-text query over title and description
 * @param custom    conditions on custom fields, matched by field key in each project
 * @param sort      one of {@link #SORT_FIELDS}; default {@code rank}
 */
public record IssueFilter(List<String> projectKeys, String text, List<UUID> statusIds, List<StatusCategory> categories,
        List<String> assignees, List<UUID> reporters, List<Priority> priorities, List<UUID> labelIds, List<UUID> typeIds,
        List<String> milestones, LocalDate dueFrom, LocalDate dueTo, Instant updatedSince, Boolean resolved,
        String parentKey, Boolean subtasks, List<CustomCondition> custom, String sort, String direction) {

    public static final List<String> SORT_FIELDS = List.of("rank", "created", "updated", "priority", "dueDate",
            "startDate", "key", "title", "status", "assignee", "estimate");

    public static IssueFilter empty() {
        return new IssueFilter(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    public IssueFilter withProjects(List<String> keys) {
        return new IssueFilter(keys, text, statusIds, categories, assignees, reporters, priorities, labelIds, typeIds,
                milestones, dueFrom, dueTo, updatedSince, resolved, parentKey, subtasks, custom, sort, direction);
    }

    public IssueFilter withSort(String newSort, String newDirection) {
        return new IssueFilter(projectKeys, text, statusIds, categories, assignees, reporters, priorities, labelIds,
                typeIds, milestones, dueFrom, dueTo, updatedSince, resolved, parentKey, subtasks, custom, newSort, newDirection);
    }

    /**
     * @param op one of {@code eq, ne, in, contains, gt, gte, lt, lte, empty, notEmpty}
     */
    public record CustomCondition(String field, String op, Object value) {
    }
}
