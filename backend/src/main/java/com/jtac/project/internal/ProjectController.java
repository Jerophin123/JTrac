package com.jtac.project.internal;

import com.jtac.identity.CurrentUser;
import com.jtac.organization.OrgContext;
import com.jtac.organization.OrganizationAccess;
import com.jtac.project.ProjectAccess;
import com.jtac.project.ProjectCatalog;
import com.jtac.project.ProjectContext;
import com.jtac.project.ProjectPermission;
import com.jtac.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
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
class ProjectController {

    private static final String PROJECTS = ApiPaths.V1 + "/orgs/{org}/projects";
    private static final String PROJECT = PROJECTS + "/{key}";

    private final OrganizationAccess organizations;
    private final ProjectAccess access;
    private final ProjectCatalog catalog;
    private final ProjectService projects;

    ProjectController(OrganizationAccess organizations, ProjectAccess access, ProjectCatalog catalog, ProjectService projects) {
        this.organizations = organizations;
        this.access = access;
        this.catalog = catalog;
        this.projects = projects;
    }

    private ProjectContext ctx(CurrentUser user, String org, String key) {
        return access.require(organizations.require(user, org), key);
    }

    record ProjectSummary(UUID id, String key, String name, String visibility, boolean archived,
            List<ProjectPermission> myPermissions) {
    }

    @GetMapping(PROJECTS)
    List<ProjectSummary> list(CurrentUser user, @PathVariable String org) {
        OrgContext ctx = organizations.require(user, org);
        return access.visible(ctx).stream()
                .map(p -> new ProjectSummary(p.projectId(), p.project().key(), p.project().name(), p.project().visibility(),
                        p.project().archived(), p.permissions().stream().sorted().toList()))
                .toList();
    }

    @PostMapping(PROJECTS)
    ResponseEntity<ProjectService.ProjectDetail> create(CurrentUser user, @PathVariable String org,
            @Valid @RequestBody CreateProject body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(projects.create(organizations.require(user, org), body.key(),
                body.name(), body.description(), body.visibility(), body.template()));
    }

    @GetMapping(PROJECT)
    ProjectService.ProjectDetail get(CurrentUser user, @PathVariable String org, @PathVariable String key) {
        return projects.detail(ctx(user, org, key));
    }

    @PatchMapping(PROJECT)
    ProjectService.ProjectDetail update(CurrentUser user, @PathVariable String org, @PathVariable String key,
            @Valid @RequestBody UpdateProject body) {
        return projects.update(ctx(user, org, key), body.name(), body.description(), body.visibility(), body.leadId(),
                body.defaultRoleId(), body.version());
    }

    @PostMapping(PROJECT + "/archive")
    ProjectService.ProjectDetail archive(CurrentUser user, @PathVariable String org, @PathVariable String key) {
        return projects.setArchived(ctx(user, org, key), true);
    }

    @PostMapping(PROJECT + "/restore")
    ProjectService.ProjectDetail restore(CurrentUser user, @PathVariable String org, @PathVariable String key) {
        return projects.setArchived(ctx(user, org, key), false);
    }

    @DeleteMapping(PROJECT)
    ResponseEntity<Void> delete(CurrentUser user, @PathVariable String org, @PathVariable String key,
            @Valid @RequestBody DeleteProject body) {
        projects.delete(ctx(user, org, key), body.confirmKey());
        return ResponseEntity.noContent().build();
    }

    // --- catalog -------------------------------------------------------------------------

    record Catalog(List<ProjectCatalog.IssueType> issueTypes, List<ProjectCatalog.Label> labels,
            List<ProjectCatalog.Milestone> milestones, List<ProjectCatalog.Role> roles) {
    }

    @GetMapping(PROJECT + "/catalog")
    Catalog catalog(CurrentUser user, @PathVariable String org, @PathVariable String key) {
        ProjectContext ctx = ctx(user, org, key);
        return new Catalog(catalog.issueTypes(ctx.projectId()), catalog.labels(ctx.projectId()),
                catalog.milestones(ctx.projectId()), catalog.roles(ctx.projectId()));
    }

    // --- roles ---------------------------------------------------------------------------

    @PostMapping(PROJECT + "/roles")
    ResponseEntity<ProjectCatalog.Role> createRole(CurrentUser user, @PathVariable String org, @PathVariable String key,
            @Valid @RequestBody RoleBody body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(projects.createRole(ctx(user, org, key), body.name(), body.permissions()));
    }

    @PatchMapping(PROJECT + "/roles/{roleId}")
    ProjectCatalog.Role updateRole(CurrentUser user, @PathVariable String org, @PathVariable String key,
            @PathVariable UUID roleId, @Valid @RequestBody RoleBody body) {
        return projects.updateRole(ctx(user, org, key), roleId, body.name(), body.permissions());
    }

    @DeleteMapping(PROJECT + "/roles/{roleId}")
    ResponseEntity<Void> deleteRole(CurrentUser user, @PathVariable String org, @PathVariable String key, @PathVariable UUID roleId) {
        projects.deleteRole(ctx(user, org, key), roleId);
        return ResponseEntity.noContent().build();
    }

    // --- members and teams ---------------------------------------------------------------

    record Access(List<ProjectService.Member> members, List<ProjectService.TeamGrant> teams) {
    }

    @GetMapping(PROJECT + "/access")
    Access accessList(CurrentUser user, @PathVariable String org, @PathVariable String key) {
        ProjectContext ctx = ctx(user, org, key);
        return new Access(projects.members(ctx), projects.teams(ctx));
    }

    @PutMapping(PROJECT + "/members/{userId}")
    ResponseEntity<Void> putMember(CurrentUser user, @PathVariable String org, @PathVariable String key,
            @PathVariable UUID userId, @Valid @RequestBody RoleRef body) {
        projects.putMember(ctx(user, org, key), userId, body.roleId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping(PROJECT + "/members/{userId}")
    ResponseEntity<Void> removeMember(CurrentUser user, @PathVariable String org, @PathVariable String key, @PathVariable UUID userId) {
        projects.removeMember(ctx(user, org, key), userId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping(PROJECT + "/teams/{teamId}")
    ResponseEntity<Void> putTeam(CurrentUser user, @PathVariable String org, @PathVariable String key,
            @PathVariable UUID teamId, @Valid @RequestBody RoleRef body) {
        projects.putTeam(ctx(user, org, key), teamId, body.roleId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping(PROJECT + "/teams/{teamId}")
    ResponseEntity<Void> removeTeam(CurrentUser user, @PathVariable String org, @PathVariable String key, @PathVariable UUID teamId) {
        projects.removeTeam(ctx(user, org, key), teamId);
        return ResponseEntity.noContent().build();
    }

    // --- issue types ---------------------------------------------------------------------

    @PostMapping(PROJECT + "/issue-types")
    ResponseEntity<ProjectCatalog.IssueType> createType(CurrentUser user, @PathVariable String org, @PathVariable String key,
            @Valid @RequestBody TypeBody body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                projects.createType(ctx(user, org, key), body.name(), body.icon(), body.color(), body.subtask()));
    }

    @PatchMapping(PROJECT + "/issue-types/{typeId}")
    ProjectCatalog.IssueType updateType(CurrentUser user, @PathVariable String org, @PathVariable String key,
            @PathVariable UUID typeId, @Valid @RequestBody TypeBody body) {
        return projects.updateType(ctx(user, org, key), typeId, body.name(), body.icon(), body.color(), body.archived());
    }

    // --- labels --------------------------------------------------------------------------

    @PostMapping(PROJECT + "/labels")
    ResponseEntity<ProjectCatalog.Label> createLabel(CurrentUser user, @PathVariable String org, @PathVariable String key,
            @Valid @RequestBody LabelBody body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(projects.createLabel(ctx(user, org, key), body.name(), body.color()));
    }

    @PatchMapping(PROJECT + "/labels/{labelId}")
    ProjectCatalog.Label updateLabel(CurrentUser user, @PathVariable String org, @PathVariable String key,
            @PathVariable UUID labelId, @Valid @RequestBody LabelBody body) {
        return projects.updateLabel(ctx(user, org, key), labelId, body.name(), body.color());
    }

    @DeleteMapping(PROJECT + "/labels/{labelId}")
    ResponseEntity<Void> deleteLabel(CurrentUser user, @PathVariable String org, @PathVariable String key, @PathVariable UUID labelId) {
        projects.deleteLabel(ctx(user, org, key), labelId);
        return ResponseEntity.noContent().build();
    }

    // --- milestones ----------------------------------------------------------------------

    @PostMapping(PROJECT + "/milestones")
    ResponseEntity<ProjectCatalog.Milestone> createMilestone(CurrentUser user, @PathVariable String org, @PathVariable String key,
            @Valid @RequestBody MilestoneBody body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(projects.saveMilestone(ctx(user, org, key), null, body.name(),
                body.description(), body.startDate(), body.dueDate(), body.state(), null));
    }

    @PatchMapping(PROJECT + "/milestones/{milestoneId}")
    ProjectCatalog.Milestone updateMilestone(CurrentUser user, @PathVariable String org, @PathVariable String key,
            @PathVariable UUID milestoneId, @Valid @RequestBody MilestoneBody body) {
        return projects.saveMilestone(ctx(user, org, key), milestoneId, body.name(), body.description(), body.startDate(),
                body.dueDate(), body.state(), body.version());
    }

    @DeleteMapping(PROJECT + "/milestones/{milestoneId}")
    ResponseEntity<Void> deleteMilestone(CurrentUser user, @PathVariable String org, @PathVariable String key,
            @PathVariable UUID milestoneId) {
        projects.deleteMilestone(ctx(user, org, key), milestoneId);
        return ResponseEntity.noContent().build();
    }

    // --- bodies --------------------------------------------------------------------------

    record CreateProject(@NotBlank @Size(max = 10) String key, @NotBlank @Size(max = 80) String name,
            @Size(max = 5000) String description, String visibility, String template) {
    }

    record UpdateProject(@NotBlank @Size(max = 80) String name, @Size(max = 5000) String description,
            @NotBlank String visibility, UUID leadId, UUID defaultRoleId, long version) {
    }

    record DeleteProject(@NotBlank String confirmKey) {
    }

    record RoleBody(@NotBlank @Size(max = 40) String name, List<ProjectPermission> permissions) {
    }

    record RoleRef(@NotNull UUID roleId) {
    }

    record TypeBody(@NotBlank @Size(max = 40) String name, String icon, String color, boolean subtask, boolean archived) {
    }

    record LabelBody(@NotBlank @Size(max = 40) String name, String color) {
    }

    record MilestoneBody(@NotBlank @Size(max = 80) String name, @Size(max = 5000) String description, LocalDate startDate,
            LocalDate dueDate, String state, Long version) {
    }
}
