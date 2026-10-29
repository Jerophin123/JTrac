package com.jtac.issue;

import com.jtac.project.ProjectCatalog;
import com.jtac.workflow.FieldAccess;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An issue as one viewer may see it: fields hidden from the viewer by the workflow's field
 * rules are {@code null}, transitions are those the viewer may use, and links only point to
 * issues the viewer can browse.
 */
public record IssueView(UUID id, String key, IssueSummary.Ref project, ProjectCatalog.IssueType type, String title,
        String description, IssueSummary.StatusRef status, Priority priority, IssueSummary.Person assignee,
        IssueSummary.Person reporter, SubtaskView parent, IssueSummary.Ref milestone, LocalDate startDate,
        LocalDate dueDate, BigDecimal estimate, List<IssueSummary.LabelRef> labels, Map<String, Object> customValues,
        Map<String, FieldAccess> fieldAccess, List<TransitionView> transitions, List<SubtaskView> subtasks,
        List<LinkView> links, List<IssueSummary.Person> watchers, boolean watching, Permissions permissions,
        Instant createdAt, Instant updatedAt, Instant resolvedAt, long version) {

    public record TransitionView(String id, String name, IssueSummary.StatusRef to) {
    }

    public record LinkView(UUID id, String type, String direction, String key, String title, IssueSummary.StatusRef status) {
    }

    public record SubtaskView(String key, String title, IssueSummary.StatusRef status, IssueSummary.Person assignee) {
    }

    public record Permissions(boolean edit, boolean transition, boolean assign, boolean delete, boolean comment,
            boolean attach, boolean manage) {
    }
}
