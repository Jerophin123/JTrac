package com.jtac.issue;

import com.jtac.organization.OrgContext;
import com.jtac.shared.web.PageResponse;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Permission-aware issue search across the projects the caller can browse. Field rules
 * apply: filters, sorting and grouping on a field only consider issues where the field is
 * visible to the caller; hidden values are never returned.
 */
public interface IssueSearch {

    PageResponse<IssueSummary> search(OrgContext org, IssueFilter filter, int page, int size);

    /** Counts of matching issues grouped by {@code groupBy} (see {@link #GROUPS}). */
    List<Bucket> countBy(OrgContext org, IssueFilter filter, String groupBy);

    /** Daily created and resolved counts for matching issues between two dates (inclusive). */
    List<DayCount> createdVsResolved(OrgContext org, IssueFilter filter, LocalDate from, LocalDate to);

    /**
     * "Blocks" dependencies among the given issues, limited to issues the caller can browse
     * (used by timelines). Each entry reads: {@code blockerId} blocks {@code blockedId}.
     */
    List<Dependency> dependencies(OrgContext org, Collection<UUID> issueIds);

    List<String> GROUPS = List.of("status", "category", "assignee", "priority", "type", "label", "milestone", "project");

    /** {@code key} is the group's id ({@code null} for "none"); {@code label} is display text. */
    record Bucket(String key, String label, long count, double estimate) {
    }

    record Dependency(UUID blockerId, String blockerKey, UUID blockedId, String blockedKey) {
    }

    record DayCount(LocalDate day, long created, long resolved) {
    }
}
