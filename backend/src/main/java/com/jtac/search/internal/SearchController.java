package com.jtac.search.internal;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SearchController {

    private static final String ORG = ApiPaths.V1 + "/orgs/{org}";

    private final OrganizationAccess organizations;
    private final GlobalSearchService search;
    private final SavedViewService views;

    SearchController(OrganizationAccess organizations, GlobalSearchService search, SavedViewService views) {
        this.organizations = organizations;
        this.search = search;
        this.views = views;
    }

    @GetMapping(ORG + "/search")
    GlobalSearchService.Results search(CurrentUser user, @PathVariable String org, @RequestParam(defaultValue = "") String q) {
        return search.search(organizations.require(user, org), q);
    }

    @GetMapping(ORG + "/views")
    List<SavedViewService.SavedView> list(CurrentUser user, @PathVariable String org,
            @RequestParam(required = false) String project) {
        return views.list(organizations.require(user, org), project);
    }

    @PostMapping(ORG + "/views")
    ResponseEntity<SavedViewService.SavedView> create(CurrentUser user, @PathVariable String org,
            @RequestBody SavedViewService.ViewInput body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(views.create(organizations.require(user, org), body));
    }

    @GetMapping(ORG + "/views/{id}")
    SavedViewService.SavedView get(CurrentUser user, @PathVariable String org, @PathVariable UUID id) {
        return views.get(organizations.require(user, org), id);
    }

    record UpdateView(long version, SavedViewService.ViewInput view) {
    }

    @PutMapping(ORG + "/views/{id}")
    SavedViewService.SavedView update(CurrentUser user, @PathVariable String org, @PathVariable UUID id,
            @RequestBody UpdateView body) {
        if (body.view() == null) {
            throw ApiErrors.invalid("view", "Required.");
        }
        return views.update(organizations.require(user, org), id, body.version(), body.view());
    }

    @DeleteMapping(ORG + "/views/{id}")
    ResponseEntity<Void> delete(CurrentUser user, @PathVariable String org, @PathVariable UUID id) {
        views.delete(organizations.require(user, org), id);
        return ResponseEntity.noContent().build();
    }
}
