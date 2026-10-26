package com.jtac.notification.internal;

import com.jtac.identity.CurrentUser;
import com.jtac.organization.OrganizationAccess;
import com.jtac.shared.web.ApiPaths;
import com.jtac.shared.web.PageResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class NotificationController {

    private static final String BASE = ApiPaths.V1 + "/orgs/{org}/notifications";

    private final OrganizationAccess organizations;
    private final NotificationService notifications;

    NotificationController(OrganizationAccess organizations, NotificationService notifications) {
        this.organizations = organizations;
        this.notifications = notifications;
    }

    @GetMapping(BASE)
    PageResponse<NotificationService.Notification> inbox(CurrentUser user, @PathVariable String org,
            @RequestParam(defaultValue = "false") boolean unread, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return notifications.inbox(organizations.require(user, org), unread, PageResponse.clampPage(page),
                PageResponse.clampSize(size, 30));
    }

    @GetMapping(BASE + "/unread-count")
    Map<String, Long> unreadCount(CurrentUser user, @PathVariable String org) {
        return Map.of("count", notifications.unreadCount(organizations.require(user, org)));
    }

    @PostMapping(BASE + "/{id}/read")
    ResponseEntity<Void> markRead(CurrentUser user, @PathVariable String org, @PathVariable UUID id) {
        notifications.markRead(organizations.require(user, org), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(BASE + "/read-all")
    Map<String, Integer> markAllRead(CurrentUser user, @PathVariable String org) {
        return Map.of("updated", notifications.markAllRead(organizations.require(user, org)));
    }

    @GetMapping(BASE + "/preferences")
    List<NotificationService.Preference> preferences(CurrentUser user, @PathVariable String org) {
        return notifications.preferences(organizations.require(user, org));
    }

    @PutMapping(BASE + "/preferences")
    List<NotificationService.Preference> updatePreferences(CurrentUser user, @PathVariable String org,
            @RequestBody List<NotificationService.Preference> body) {
        return notifications.updatePreferences(organizations.require(user, org), body);
    }
}
