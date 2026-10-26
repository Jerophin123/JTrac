package com.jtac.issue.internal;

import com.jtac.issue.Priority;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Mutable working copy of an issue's editable values, tracking which fields changed. */
final class IssueDraft {

    UUID typeId;
    String title;
    String description;
    UUID statusId;
    Priority priority;
    UUID assigneeId;
    UUID parentId;
    UUID milestoneId;
    LocalDate startDate;
    LocalDate dueDate;
    BigDecimal estimate;
    List<UUID> labelIds;
    final Map<String, Object> customValues;
    final Set<String> changed = new LinkedHashSet<>();

    private IssueDraft(Map<String, Object> customValues) {
        this.customValues = customValues;
    }

    static IssueDraft empty() {
        IssueDraft draft = new IssueDraft(new LinkedHashMap<>());
        draft.priority = Priority.NONE;
        draft.labelIds = new ArrayList<>();
        return draft;
    }

    static IssueDraft of(IssueRecord issue) {
        IssueDraft draft = new IssueDraft(new LinkedHashMap<>(issue.customValues()));
        draft.typeId = issue.typeId();
        draft.title = issue.title();
        draft.description = issue.description();
        draft.statusId = issue.statusId();
        draft.priority = issue.priority();
        draft.assigneeId = issue.assigneeId();
        draft.parentId = issue.parentId();
        draft.milestoneId = issue.milestoneId();
        draft.startDate = issue.startDate();
        draft.dueDate = issue.dueDate();
        draft.estimate = issue.estimate();
        draft.labelIds = new ArrayList<>(issue.labelIds());
        return draft;
    }
}
