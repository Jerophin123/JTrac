package com.jtac.planning;

import com.jtac.issue.IssueFilter;
import com.jtac.organization.OrgContext;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Progress of a project's milestones as seen by the caller. */
public interface MilestoneProgress {

    /**
     * @param base additional constraints (e.g. from a dashboard widget); the project and
     *             milestone constraints are set by this method
     */
    List<Progress> forProject(OrgContext org, String projectKey, IssueFilter base);

    /** {@code progress} is done / (total - canceled), or {@code null} if nothing is countable. */
    record Progress(UUID id, String name, LocalDate startDate, LocalDate dueDate, String state, long total, long done,
            long canceled, Double progress) {
    }
}
