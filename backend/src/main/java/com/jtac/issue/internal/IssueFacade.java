package com.jtac.issue.internal;

import com.jtac.issue.IssueActivityEntry;
import com.jtac.issue.IssueDetails;
import com.jtac.issue.IssueEditing;
import com.jtac.issue.IssueView;
import com.jtac.organization.OrgContext;
import com.jtac.project.ProjectAccess;
import com.jtac.project.ProjectContext;
import com.jtac.shared.web.ApiErrors;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link IssueDetails} and {@link IssueEditing} for other modules. Resolves issues exactly
 * like {@link IssueController} and delegates to the same services, so every rule applies.
 */
@Service
class IssueFacade implements IssueDetails, IssueEditing {

    private final ProjectAccess projects;
    private final IssueStore store;
    private final IssueService issues;
    private final IssueViewService views;

    IssueFacade(ProjectAccess projects, IssueStore store, IssueService issues, IssueViewService views) {
        this.projects = projects;
        this.store = store;
        this.issues = issues;
        this.views = views;
    }

    private record Resolved(ProjectContext ctx, IssueRecord issue) {
    }

    private Resolved resolve(OrgContext org, String issueKey) {
        IssueRecord issue = store.byKey(org.organizationId(), issueKey).orElseThrow(() -> ApiErrors.notFound("Issue"));
        ProjectContext ctx = projects.visible(org, List.of(issue.projectId())).get(issue.projectId());
        if (ctx == null) {
            throw ApiErrors.notFound("Issue");
        }
        return new Resolved(ctx, issue);
    }

    @Override
    @Transactional(readOnly = true)
    public IssueView view(OrgContext org, String issueKey) {
        Resolved r = resolve(org, issueKey);
        return views.view(r.ctx(), r.issue());
    }

    @Override
    @Transactional(readOnly = true)
    public List<IssueActivityEntry> activity(OrgContext org, String issueKey) {
        Resolved r = resolve(org, issueKey);
        return views.activity(r.ctx(), r.issue());
    }

    @Override
    @Transactional
    public IssueView update(OrgContext org, String issueKey, Map<String, Object> changes, long version) {
        Resolved r = resolve(org, issueKey);
        return views.view(r.ctx(), issues.update(r.ctx(), r.issue(), changes, version));
    }

    @Override
    @Transactional
    public IssueView link(OrgContext org, String issueKey, String targetKey, String type) {
        Resolved r = resolve(org, issueKey);
        Resolved target = resolve(org, targetKey);
        issues.addLink(r.ctx(), r.issue(), target.issue(), type);
        return views.view(r.ctx(), r.issue());
    }

    @Override
    @Transactional
    public void unlink(OrgContext org, String issueKey, UUID linkId) {
        Resolved r = resolve(org, issueKey);
        issues.removeLink(r.ctx(), r.issue(), linkId);
    }
}
