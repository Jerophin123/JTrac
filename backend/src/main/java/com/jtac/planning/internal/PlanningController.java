package com.jtac.planning.internal;

import com.jtac.identity.CurrentUser;
import com.jtac.issue.IssueFilter;
import com.jtac.organization.OrganizationAccess;
import com.jtac.planning.MilestoneProgress;
import com.jtac.shared.web.ApiPaths;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PlanningController {

    private static final String PROJECT = ApiPaths.V1 + "/orgs/{org}/projects/{key}";

    private final OrganizationAccess organizations;
    private final PlanningService planning;

    PlanningController(OrganizationAccess organizations, PlanningService planning) {
        this.organizations = organizations;
        this.planning = planning;
    }

    record TimelineRequest(IssueFilter filter) {
    }

    @PostMapping(PROJECT + "/timeline")
    PlanningService.Timeline timeline(CurrentUser user, @PathVariable String org, @PathVariable String key,
            @RequestBody(required = false) TimelineRequest body) {
        return planning.timeline(organizations.require(user, org), key, body == null ? null : body.filter());
    }

    @GetMapping(PROJECT + "/milestones/progress")
    List<MilestoneProgress.Progress> progress(CurrentUser user, @PathVariable String org, @PathVariable String key) {
        return planning.forProject(organizations.require(user, org), key, IssueFilter.empty());
    }
}
