package com.jtac.reporting.internal;

import com.jtac.identity.CurrentUser;
import com.jtac.organization.OrganizationAccess;
import com.jtac.shared.web.ApiErrors;
import com.jtac.shared.web.ApiPaths;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DashboardController {

    private static final String BASE = ApiPaths.V1 + "/orgs/{org}/dashboards";

    private final OrganizationAccess organizations;
    private final DashboardService dashboards;

    DashboardController(OrganizationAccess organizations, DashboardService dashboards) {
        this.organizations = organizations;
        this.dashboards = dashboards;
    }

    @GetMapping(BASE)
    List<DashboardService.DashboardSummary> list(CurrentUser user, @PathVariable String org) {
        return dashboards.list(organizations.require(user, org));
    }

    @PostMapping(BASE)
    ResponseEntity<DashboardService.Dashboard> create(CurrentUser user, @PathVariable String org,
            @RequestBody DashboardService.DashboardInput body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dashboards.create(organizations.require(user, org), body));
    }

    @GetMapping(BASE + "/{id}")
    DashboardService.Dashboard get(CurrentUser user, @PathVariable String org, @PathVariable UUID id) {
        return dashboards.get(organizations.require(user, org), id);
    }

    record UpdateDashboard(long version, DashboardService.DashboardInput dashboard) {
    }

    @PutMapping(BASE + "/{id}")
    DashboardService.Dashboard update(CurrentUser user, @PathVariable String org, @PathVariable UUID id,
            @RequestBody UpdateDashboard body) {
        if (body.dashboard() == null) {
            throw ApiErrors.invalid("dashboard", "Required.");
        }
        return dashboards.update(organizations.require(user, org), id, body.version(), body.dashboard());
    }

    @DeleteMapping(BASE + "/{id}")
    ResponseEntity<Void> delete(CurrentUser user, @PathVariable String org, @PathVariable UUID id) {
        dashboards.delete(organizations.require(user, org), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(BASE + "/{id}/widgets/{widgetId}/data")
    DashboardService.WidgetData data(CurrentUser user, @PathVariable String org, @PathVariable UUID id,
            @PathVariable UUID widgetId) {
        return dashboards.data(organizations.require(user, org), id, widgetId);
    }

    @PostMapping(ApiPaths.V1 + "/orgs/{org}/widgets/preview")
    DashboardService.WidgetData preview(CurrentUser user, @PathVariable String org,
            @RequestBody DashboardService.WidgetInput body) {
        return dashboards.preview(organizations.require(user, org), body);
    }
}
