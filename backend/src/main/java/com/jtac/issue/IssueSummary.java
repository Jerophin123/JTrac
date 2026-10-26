package com.jtac.issue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An issue as shown in lists, boards and exports. Fields hidden from the viewer by the
 * workflow's field rules are {@code null} and listed in {@code hiddenFields}.
 */
public record IssueSummary(UUID id, String key, String projectKey, UUID projectId, String title, Ref type, StatusRef status,
        Priority priority, Person assignee, Person reporter, List<LabelRef> labels, LocalDate startDate, LocalDate dueDate,
        BigDecimal estimate, Ref milestone, String parentKey, long rank, Map<String, Object> customValues, int subtaskCount,
        int subtasksDone, Instant createdAt, Instant updatedAt, Instant resolvedAt, List<String> hiddenFields) {

    public record Ref(UUID id, String name) {
    }

    public record StatusRef(UUID id, String name, String category) {
    }

    public record Person(UUID id, String displayName, String handle) {
    }

    public record LabelRef(UUID id, String name, String color) {
    }
}
