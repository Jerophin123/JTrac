package com.jtac.organization.internal;

import com.jtac.audit.AuditLog;
import com.jtac.audit.AuditRecord;
import com.jtac.identity.CurrentUser;
import com.jtac.identity.UserDirectory;
import com.jtac.identity.UserSummary;
import com.jtac.organization.OrgContext;
import com.jtac.organization.OrgRole;
import com.jtac.organization.OrganizationSettings;
import com.jtac.shared.web.ApiPaths;
import com.jtac.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OrganizationController {

    private static final String ORG = ApiPaths.V1 + "/orgs/{org}";

    private final OrganizationService organizations;
    private final MembershipService memberships;
    private final InvitationService invitations;
    private final TeamService teams;
    private final AuditLog audit;
    private final UserDirectory users;

    OrganizationController(OrganizationService organizations, MembershipService memberships, InvitationService invitations,
            TeamService teams, AuditLog audit, UserDirectory users) {
        this.organizations = organizations;
        this.memberships = memberships;
        this.invitations = invitations;
        this.teams = teams;
        this.audit = audit;
        this.users = users;
    }

    private OrgContext ctx(CurrentUser user, String slug) {
        return organizations.require(user, slug);
    }

    // --- organizations -------------------------------------------------------------------

    @GetMapping(ApiPaths.V1 + "/orgs")
    List<OrganizationService.OrgSummary> mine(CurrentUser user) {
        return organizations.listMine(user);
    }

    @PostMapping(ApiPaths.V1 + "/orgs")
    ResponseEntity<OrganizationService.OrgSummary> create(CurrentUser user, @Valid @RequestBody CreateOrg body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(organizations.create(user, body.name(), body.slug()));
    }

    @GetMapping(ORG)
    OrganizationService.OrgDetail get(CurrentUser user, @PathVariable String org) {
        return organizations.detail(ctx(user, org));
    }

    @PatchMapping(ORG)
    OrganizationService.OrgDetail update(CurrentUser user, @PathVariable String org, @Valid @RequestBody UpdateOrg body) {
        return organizations.update(ctx(user, org), body.name(), body.slug(), body.settings(), body.version());
    }

    @DeleteMapping(ORG)
    ResponseEntity<Void> delete(CurrentUser user, @PathVariable String org, @Valid @RequestBody DeleteOrg body) {
        organizations.delete(ctx(user, org), body.confirmName());
        return ResponseEntity.noContent().build();
    }

    // --- members -------------------------------------------------------------------------

    @GetMapping(ORG + "/members")
    PageResponse<MembershipService.Member> members(CurrentUser user, @PathVariable String org,
            @RequestParam(required = false) String q, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return memberships.list(ctx(user, org), q, PageResponse.clampPage(page), PageResponse.clampSize(size, 50));
    }

    @PatchMapping(ORG + "/members/{userId}")
    ResponseEntity<Void> changeRole(CurrentUser user, @PathVariable String org, @PathVariable UUID userId,
            @Valid @RequestBody ChangeRole body) {
        memberships.changeRole(ctx(user, org), userId, body.role());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping(ORG + "/members/{userId}")
    ResponseEntity<Void> removeMember(CurrentUser user, @PathVariable String org, @PathVariable UUID userId) {
        memberships.remove(ctx(user, org), userId);
        return ResponseEntity.noContent().build();
    }

    // --- invitations ---------------------------------------------------------------------

    @GetMapping(ORG + "/invitations")
    List<InvitationService.Invitation> invitations(CurrentUser user, @PathVariable String org) {
        return invitations.pending(ctx(user, org));
    }

    @PostMapping(ORG + "/invitations")
    ResponseEntity<InvitationService.Invitation> invite(CurrentUser user, @PathVariable String org,
            @Valid @RequestBody Invite body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(invitations.invite(ctx(user, org), body.email(), body.role()));
    }

    @DeleteMapping(ORG + "/invitations/{id}")
    ResponseEntity<Void> revokeInvitation(CurrentUser user, @PathVariable String org, @PathVariable UUID id) {
        invitations.revoke(ctx(user, org), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(ORG + "/invitations/{id}/resend")
    ResponseEntity<Void> resendInvitation(CurrentUser user, @PathVariable String org, @PathVariable UUID id) {
        invitations.resend(ctx(user, org), id);
        return ResponseEntity.accepted().build();
    }

    @GetMapping(ApiPaths.V1 + "/public/invitations/{token}")
    InvitationService.InvitationPreview previewInvitation(@PathVariable String token) {
        return invitations.preview(token);
    }

    @PostMapping(ApiPaths.V1 + "/public/invitations/{token}/register")
    AcceptResult acceptAsNewUser(@PathVariable String token, @Valid @RequestBody NewAccount body) {
        return new AcceptResult(invitations.acceptAsNewUser(token, body.displayName(), body.password(), body.acceptTerms()));
    }

    @PostMapping(ApiPaths.V1 + "/invitations/{token}/accept")
    AcceptResult accept(CurrentUser user, @PathVariable String token) {
        return new AcceptResult(invitations.acceptAsUser(user, token));
    }

    // --- teams ---------------------------------------------------------------------------

    @GetMapping(ORG + "/teams")
    List<TeamService.Team> teams(CurrentUser user, @PathVariable String org) {
        return teams.list(ctx(user, org));
    }

    @PostMapping(ORG + "/teams")
    ResponseEntity<TeamService.Team> createTeam(CurrentUser user, @PathVariable String org, @Valid @RequestBody TeamBody body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(teams.create(ctx(user, org), body.name(), body.description()));
    }

    @PatchMapping(ORG + "/teams/{teamId}")
    TeamService.Team updateTeam(CurrentUser user, @PathVariable String org, @PathVariable UUID teamId,
            @Valid @RequestBody TeamBody body) {
        return teams.update(ctx(user, org), teamId, body.name(), body.description());
    }

    @DeleteMapping(ORG + "/teams/{teamId}")
    ResponseEntity<Void> deleteTeam(CurrentUser user, @PathVariable String org, @PathVariable UUID teamId) {
        teams.delete(ctx(user, org), teamId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping(ORG + "/teams/{teamId}/members/{userId}")
    ResponseEntity<Void> addTeamMember(CurrentUser user, @PathVariable String org, @PathVariable UUID teamId,
            @PathVariable UUID userId) {
        teams.addMember(ctx(user, org), teamId, userId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping(ORG + "/teams/{teamId}/members/{userId}")
    ResponseEntity<Void> removeTeamMember(CurrentUser user, @PathVariable String org, @PathVariable UUID teamId,
            @PathVariable UUID userId) {
        teams.removeMember(ctx(user, org), teamId, userId);
        return ResponseEntity.noContent().build();
    }

    // --- audit log -----------------------------------------------------------------------

    @GetMapping(ORG + "/audit")
    PageResponse<AuditView> audit(CurrentUser user, @PathVariable String org, @RequestParam(required = false) String action,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        OrgContext ctx = ctx(user, org).requireAdmin();
        PageResponse<AuditRecord> records = audit.forOrganization(ctx.organizationId(), action, PageResponse.clampPage(page),
                PageResponse.clampSize(size, 50));
        Set<UUID> actorIds = new HashSet<>();
        records.items().forEach(r -> {
            if (r.actorId() != null) {
                actorIds.add(r.actorId());
            }
        });
        Map<UUID, UserSummary> actors = users.findAll(actorIds);
        return records.map(r -> new AuditView(r.id(), r.action(), r.actorId(),
                r.actorId() == null || !actors.containsKey(r.actorId()) ? null : actors.get(r.actorId()).displayName(),
                r.targetType(), r.targetId(), r.details(), r.clientAddress(), r.occurredAt()));
    }

    // --- request/response bodies ---------------------------------------------------------

    record CreateOrg(@NotBlank @Size(max = 80) String name, @Size(max = 40) String slug) {
    }

    record UpdateOrg(@NotBlank @Size(max = 80) String name, @Size(max = 40) String slug, OrganizationSettings settings,
            long version) {
    }

    record DeleteOrg(@NotBlank String confirmName) {
    }

    record ChangeRole(@NotNull OrgRole role) {
    }

    record Invite(@NotBlank @Size(max = 320) String email, @NotNull OrgRole role) {
    }

    record NewAccount(@NotBlank @Size(max = 120) String displayName, @NotBlank @Size(max = 1024) String password,
            Boolean acceptTerms) {
        @Override
        public String toString() {
            return "NewAccount[displayName=" + displayName + ", password=****, acceptTerms=" + acceptTerms + "]";
        }
    }

    record AcceptResult(String organizationSlug) {
    }

    record TeamBody(@NotBlank @Size(max = 60) String name, @Size(max = 500) String description) {
    }

    record AuditView(UUID id, String action, UUID actorId, String actorName, String targetType, String targetId,
            Map<String, Object> details, String clientAddress, Instant occurredAt) {
    }
}
