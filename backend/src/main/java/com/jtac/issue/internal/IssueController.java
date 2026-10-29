package com.jtac.issue.internal;

import com.jtac.identity.CurrentUser;
import com.jtac.issue.IssueActivityEntry;
import com.jtac.issue.IssueFilter;
import com.jtac.issue.IssueSearch;
import com.jtac.issue.IssueSummary;
import com.jtac.issue.IssueView;
import com.jtac.issue.NewIssue;
import com.jtac.issue.Priority;
import com.jtac.organization.OrgContext;
import com.jtac.organization.OrganizationAccess;
import com.jtac.project.ProjectAccess;
import com.jtac.project.ProjectContext;
import com.jtac.shared.web.ApiErrors;
import com.jtac.shared.web.ApiPaths;
import com.jtac.shared.web.ApiProblemException;
import com.jtac.shared.web.PageResponse;
import com.jtac.workflow.Status;
import com.jtac.workflow.Workflow;
import com.jtac.workflow.WorkflowCatalog;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class IssueController {

    private static final String ORG = ApiPaths.V1 + "/orgs/{org}";
    private static final String ISSUE = ORG + "/issues/{issueKey}";

    private final OrganizationAccess organizations;
    private final ProjectAccess projects;
    private final IssueStore store;
    private final IssueService issues;
    private final IssueViewService views;
    private final IssueSearch search;
    private final WorkflowCatalog workflows;

    IssueController(OrganizationAccess organizations, ProjectAccess projects, IssueStore store, IssueService issues,
            IssueViewService views, IssueSearch search, WorkflowCatalog workflows) {
        this.organizations = organizations;
        this.projects = projects;
        this.store = store;
        this.issues = issues;
        this.views = views;
        this.search = search;
        this.workflows = workflows;
    }

    private record Resolved(ProjectContext ctx, IssueRecord issue) {
    }

    private Resolved resolve(CurrentUser user, String org, String issueKey) {
        OrgContext orgCtx = organizations.require(user, org);
        IssueRecord issue = store.byKey(orgCtx.organizationId(), issueKey).orElseThrow(() -> ApiErrors.notFound("Issue"));
        ProjectContext ctx = projects.visible(orgCtx, List.of(issue.projectId())).get(issue.projectId());
        if (ctx == null) {
            throw ApiErrors.notFound("Issue");
        }
        return new Resolved(ctx, issue);
    }

    // --- single issue --------------------------------------------------------------------

    @PostMapping(ORG + "/projects/{key}/issues")
    ResponseEntity<IssueView> create(CurrentUser user, @PathVariable String org, @PathVariable String key,
            @Valid @RequestBody CreateIssue body) {
        ProjectContext ctx = projects.require(organizations.require(user, org), key);
        IssueRecord created = issues.createIssue(ctx, new NewIssue(body.typeId(), body.title(), body.description(),
                body.priority(), body.assigneeId(), body.parentKey(), body.milestoneId(), body.startDate(), body.dueDate(),
                body.estimate(), body.labelIds(), body.customValues()));
        return ResponseEntity.status(HttpStatus.CREATED).body(views.view(ctx, created));
    }

    @GetMapping(ISSUE)
    IssueView get(CurrentUser user, @PathVariable String org, @PathVariable String issueKey) {
        Resolved r = resolve(user, org, issueKey);
        return views.view(r.ctx(), r.issue());
    }

    @PatchMapping(ISSUE)
    IssueView update(CurrentUser user, @PathVariable String org, @PathVariable String issueKey,
            @Valid @RequestBody UpdateIssue body) {
        Resolved r = resolve(user, org, issueKey);
        IssueRecord saved = issues.update(r.ctx(), r.issue(), body.changes(), body.version());
        return views.view(r.ctx(), saved);
    }

    @DeleteMapping(ISSUE)
    ResponseEntity<Void> delete(CurrentUser user, @PathVariable String org, @PathVariable String issueKey) {
        Resolved r = resolve(user, org, issueKey);
        issues.delete(r.ctx(), r.issue());
        return ResponseEntity.noContent().build();
    }

    @PostMapping(ISSUE + "/move")
    IssueView move(CurrentUser user, @PathVariable String org, @PathVariable String issueKey,
            @Valid @RequestBody Move body) {
        Resolved r = resolve(user, org, issueKey);
        IssueRecord moved = issues.move(r.ctx(), r.issue(), body.statusId(), body.afterId(), body.beforeId(), body.version());
        return views.view(r.ctx(), moved);
    }

    @GetMapping(ISSUE + "/activity")
    List<IssueActivityEntry> activity(CurrentUser user, @PathVariable String org, @PathVariable String issueKey) {
        Resolved r = resolve(user, org, issueKey);
        return views.activity(r.ctx(), r.issue());
    }

    @PostMapping(ISSUE + "/links")
    ResponseEntity<IssueView> link(CurrentUser user, @PathVariable String org, @PathVariable String issueKey,
            @Valid @RequestBody NewLink body) {
        Resolved r = resolve(user, org, issueKey);
        Resolved target = resolve(user, org, body.targetKey());
        issues.addLink(r.ctx(), r.issue(), target.issue(), body.type());
        return ResponseEntity.status(HttpStatus.CREATED).body(views.view(r.ctx(), r.issue()));
    }

    @DeleteMapping(ISSUE + "/links/{linkId}")
    ResponseEntity<Void> unlink(CurrentUser user, @PathVariable String org, @PathVariable String issueKey, @PathVariable UUID linkId) {
        Resolved r = resolve(user, org, issueKey);
        issues.removeLink(r.ctx(), r.issue(), linkId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping(ISSUE + "/watch")
    ResponseEntity<Void> watch(CurrentUser user, @PathVariable String org, @PathVariable String issueKey) {
        Resolved r = resolve(user, org, issueKey);
        issues.setWatching(r.ctx(), r.issue(), true);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping(ISSUE + "/watch")
    ResponseEntity<Void> unwatch(CurrentUser user, @PathVariable String org, @PathVariable String issueKey) {
        Resolved r = resolve(user, org, issueKey);
        issues.setWatching(r.ctx(), r.issue(), false);
        return ResponseEntity.noContent().build();
    }

    // --- search, aggregates, board ---------------------------------------------------------

    @PostMapping(ORG + "/issues/search")
    PageResponse<IssueSummary> search(CurrentUser user, @PathVariable String org, @RequestBody SearchRequest body) {
        OrgContext ctx = organizations.require(user, org);
        return search.search(ctx, body.filter() == null ? IssueFilter.empty() : body.filter(),
                PageResponse.clampPage(body.page()), PageResponse.clampSize(body.size(), 50));
    }

    @PostMapping(ORG + "/issues/aggregate")
    List<IssueSearch.Bucket> aggregate(CurrentUser user, @PathVariable String org, @RequestBody AggregateRequest body) {
        OrgContext ctx = organizations.require(user, org);
        return search.countBy(ctx, body.filter() == null ? IssueFilter.empty() : body.filter(), body.groupBy());
    }

    record BoardColumn(Status status, long total, List<IssueSummary> issues) {
    }

    record Board(List<BoardColumn> columns, int workflowVersion) {
    }

    @PostMapping(ORG + "/projects/{key}/board")
    Board board(CurrentUser user, @PathVariable String org, @PathVariable String key, @RequestBody(required = false) SearchRequest body,
            @RequestParam(defaultValue = "100") int perColumn) {
        OrgContext orgCtx = organizations.require(user, org);
        ProjectContext ctx = projects.require(orgCtx, key);
        Workflow workflow = workflows.published(ctx.projectId());
        IssueFilter base = (body == null || body.filter() == null ? IssueFilter.empty() : body.filter())
                .withProjects(List.of(ctx.project().key())).withSort("rank", "asc");
        int limit = Math.max(1, Math.min(perColumn, 200));
        List<BoardColumn> columns = new ArrayList<>();
        for (Status status : workflow.boardStatuses()) {
            IssueFilter columnFilter = new IssueFilter(base.projectKeys(), base.text(), List.of(status.id()), base.categories(),
                    base.assignees(), base.reporters(), base.priorities(), base.labelIds(), base.typeIds(), base.milestones(),
                    base.dueFrom(), base.dueTo(), base.updatedSince(), base.resolved(), base.parentKey(), base.subtasks(),
                    base.custom(), base.sort(), base.direction());
            PageResponse<IssueSummary> page = search.search(orgCtx, columnFilter, 0, limit);
            columns.add(new BoardColumn(status, page.total(), page.items()));
        }
        return new Board(columns, workflow.versionNo());
    }

    // --- bulk ----------------------------------------------------------------------------

    record BulkResult(String key, boolean ok, String error) {
    }

    /** Applies the same change (or deletion) to many issues; each issue succeeds or fails on its own. */
    @PostMapping(ORG + "/issues/bulk")
    List<BulkResult> bulk(CurrentUser user, @PathVariable String org, @Valid @RequestBody Bulk body) {
        if (body.keys().size() > 200) {
            throw ApiErrors.invalid("keys", "At most 200 issues at once.");
        }
        if (!body.delete() && (body.changes() == null || body.changes().isEmpty())) {
            throw ApiErrors.invalid("changes", "Choose a change to apply.");
        }
        List<BulkResult> results = new ArrayList<>();
        for (String key : body.keys()) {
            try {
                Resolved r = resolve(user, org, key);
                if (body.delete()) {
                    issues.delete(r.ctx(), r.issue());
                } else {
                    issues.update(r.ctx(), r.issue(), body.changes(), r.issue().version());
                }
                results.add(new BulkResult(key, true, null));
            } catch (ApiErrors.ValidationFailed e) {
                results.add(new BulkResult(key, false, e.errors().isEmpty() ? e.getMessage() : e.errors().getFirst().message()));
            } catch (ApiProblemException e) {
                results.add(new BulkResult(key, false, e.getMessage()));
            } catch (OptimisticLockingFailureException e) {
                results.add(new BulkResult(key, false, "Changed by someone else; try again."));
            }
        }
        return results;
    }

    // --- bodies --------------------------------------------------------------------------

    record CreateIssue(UUID typeId, @NotBlank @Size(max = 255) String title, @Size(max = 100_000) String description,
            Priority priority, UUID assigneeId, String parentKey, UUID milestoneId, LocalDate startDate, LocalDate dueDate,
            BigDecimal estimate, List<UUID> labelIds, Map<String, Object> customValues) {
    }

    record UpdateIssue(@NotNull Map<String, Object> changes, long version) {
    }

    record Move(UUID statusId, UUID afterId, UUID beforeId, long version) {
    }

    record NewLink(@NotBlank String targetKey, @NotBlank String type) {
    }

    record SearchRequest(IssueFilter filter, Integer page, Integer size) {
    }

    record AggregateRequest(IssueFilter filter, @NotBlank String groupBy) {
    }

    record Bulk(@NotEmpty List<String> keys, Map<String, Object> changes, boolean delete) {
    }
}
