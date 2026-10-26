package com.jtac.planning.internal;

import com.jtac.issue.IssueFilter;
import com.jtac.issue.IssueSearch;
import com.jtac.issue.IssueSummary;
import com.jtac.organization.OrgContext;
import com.jtac.planning.MilestoneProgress;
import com.jtac.project.ProjectAccess;
import com.jtac.project.ProjectCatalog;
import com.jtac.project.ProjectContext;
import com.jtac.shared.web.PageResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PlanningService implements MilestoneProgress {

    /** Upper bound of issues scanned for one timeline (the response reports truncation). */
    static final int MAX_SCANNED = 2_000;
    private static final int PAGE = 200;

    private final IssueSearch issues;
    private final ProjectAccess projects;
    private final ProjectCatalog catalog;

    PlanningService(IssueSearch issues, ProjectAccess projects, ProjectCatalog catalog) {
        this.issues = issues;
        this.projects = projects;
        this.catalog = catalog;
    }

    record Timeline(String projectKey, List<IssueSummary> scheduled, long unscheduled, boolean truncated,
            List<IssueSearch.Dependency> dependencies, List<Progress> milestones) {
    }

    /**
     * Issues with a start or due date the caller may see (dates hidden by field rules count as
     * unscheduled), their "blocks" dependencies, and milestone progress.
     */
    @Transactional(readOnly = true)
    Timeline timeline(OrgContext org, String projectKey, IssueFilter base) {
        ProjectContext project = projects.require(org, projectKey);
        String key = project.project().key();
        IssueFilter filter = (base == null ? IssueFilter.empty() : base).withProjects(List.of(key)).withSort("startDate", "asc");
        List<IssueSummary> scheduled = new ArrayList<>();
        long unscheduled = 0;
        long scanned = 0;
        long total;
        int page = 0;
        do {
            PageResponse<IssueSummary> result = issues.search(org, filter, page++, PAGE);
            total = result.total();
            for (IssueSummary issue : result.items()) {
                if (issue.startDate() != null || issue.dueDate() != null) {
                    scheduled.add(issue);
                } else {
                    unscheduled++;
                }
            }
            scanned += result.items().size();
            if (result.items().isEmpty()) {
                break;
            }
        } while (scanned < total && scanned < MAX_SCANNED);
        List<UUID> ids = scheduled.stream().map(IssueSummary::id).toList();
        return new Timeline(key, scheduled, unscheduled, scanned < total, issues.dependencies(org, ids),
                forProject(org, key, IssueFilter.empty()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Progress> forProject(OrgContext org, String projectKey, IssueFilter base) {
        ProjectContext project = projects.require(org, projectKey);
        IssueFilter b = base == null ? IssueFilter.empty() : base;
        List<Progress> result = new ArrayList<>();
        for (ProjectCatalog.Milestone m : catalog.milestones(project.projectId())) {
            IssueFilter filter = new IssueFilter(List.of(project.project().key()), b.text(), b.statusIds(), b.categories(),
                    b.assignees(), b.reporters(), b.priorities(), b.labelIds(), b.typeIds(), List.of(m.id().toString()),
                    b.dueFrom(), b.dueTo(), b.updatedSince(), b.resolved(), b.parentKey(), b.subtasks(), b.custom(), null, null);
            long total = 0;
            long done = 0;
            long canceled = 0;
            for (IssueSearch.Bucket bucket : issues.countBy(org, filter, "category")) {
                total += bucket.count();
                if ("DONE".equals(bucket.key())) {
                    done += bucket.count();
                } else if ("CANCELED".equals(bucket.key())) {
                    canceled += bucket.count();
                }
            }
            long countable = total - canceled;
            result.add(new Progress(m.id(), m.name(), m.startDate(), m.dueDate(), m.state(), total, done, canceled,
                    countable == 0 ? null : (double) done / countable));
        }
        return result;
    }
}
