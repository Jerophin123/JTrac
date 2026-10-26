package com.jtac.realtime.internal;

import com.jtac.identity.CurrentUser;
import com.jtac.organization.OrgContext;
import com.jtac.organization.OrganizationAccess;
import com.jtac.shared.web.ApiPaths;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
class RealtimeController {

    private final OrganizationAccess organizations;
    private final SseHub hub;

    RealtimeController(OrganizationAccess organizations, SseHub hub) {
        this.organizations = organizations;
        this.hub = hub;
    }

    @GetMapping(path = ApiPaths.V1 + "/orgs/{org}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(CurrentUser user, @PathVariable String org) {
        OrgContext ctx = organizations.require(user, org);
        return hub.connect(ctx.organizationId(), ctx.actorId());
    }
}
