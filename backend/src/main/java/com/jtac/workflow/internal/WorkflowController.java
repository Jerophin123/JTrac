package com.jtac.workflow.internal;

import com.jtac.identity.CurrentUser;
import com.jtac.organization.OrganizationAccess;
import com.jtac.project.ProjectAccess;
import com.jtac.project.ProjectContext;
import com.jtac.shared.web.ApiPaths;
import com.jtac.workflow.Status;
import com.jtac.workflow.StatusCategory;
import com.jtac.workflow.WorkflowDefinition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class WorkflowController {

    private static final String WORKFLOW = ApiPaths.V1 + "/orgs/{org}/projects/{key}/workflow";

    private final OrganizationAccess organizations;
    private final ProjectAccess projects;
    private final WorkflowService workflows;

    WorkflowController(OrganizationAccess organizations, ProjectAccess projects, WorkflowService workflows) {
        this.organizations = organizations;
        this.projects = projects;
        this.workflows = workflows;
    }

    private ProjectContext ctx(CurrentUser user, String org, String key) {
        return projects.require(organizations.require(user, org), key);
    }

    @GetMapping(WORKFLOW)
    WorkflowService.Overview overview(CurrentUser user, @PathVariable String org, @PathVariable String key) {
        return workflows.overview(ctx(user, org, key));
    }

    @PostMapping(WORKFLOW + "/statuses")
    ResponseEntity<Status> createStatus(CurrentUser user, @PathVariable String org, @PathVariable String key,
            @Valid @RequestBody StatusBody body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(workflows.createStatus(ctx(user, org, key), body.name(), body.category()));
    }

    @PatchMapping(WORKFLOW + "/statuses/{statusId}")
    Status updateStatus(CurrentUser user, @PathVariable String org, @PathVariable String key, @PathVariable UUID statusId,
            @Valid @RequestBody StatusBody body) {
        return workflows.updateStatus(ctx(user, org, key), statusId, body.name(), body.category());
    }

    @PutMapping(WORKFLOW + "/draft")
    WorkflowService.VersionInfo saveDraft(CurrentUser user, @PathVariable String org, @PathVariable String key,
            @RequestBody WorkflowDefinition body) {
        return workflows.saveDraft(ctx(user, org, key), body);
    }

    @DeleteMapping(WORKFLOW + "/draft")
    ResponseEntity<Void> discardDraft(CurrentUser user, @PathVariable String org, @PathVariable String key) {
        workflows.discardDraft(ctx(user, org, key));
        return ResponseEntity.noContent().build();
    }

    @GetMapping(WORKFLOW + "/draft/impact")
    WorkflowService.Impact impact(CurrentUser user, @PathVariable String org, @PathVariable String key) {
        return workflows.impact(ctx(user, org, key));
    }

    @PostMapping(WORKFLOW + "/draft/publish")
    WorkflowService.VersionInfo publish(CurrentUser user, @PathVariable String org, @PathVariable String key,
            @RequestBody(required = false) Publish body) {
        return workflows.publish(ctx(user, org, key), body == null ? Map.of() : body.mapping());
    }

    record StatusBody(@NotBlank @Size(max = 40) String name, @NotNull StatusCategory category) {
    }

    record Publish(Map<UUID, UUID> mapping) {
        Publish {
            mapping = mapping == null ? Map.of() : mapping;
        }
    }
}
