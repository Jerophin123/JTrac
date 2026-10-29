package com.jtac.notification;

import com.jtac.organization.OrgContext;
import com.jtac.shared.web.PageResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The caller's own in-app notifications and notification preferences in an organization (read-only). */
public interface Inbox {

    PageResponse<Item> page(OrgContext org, boolean unreadOnly, int page, int size);

    List<Preference> preferences(OrgContext org);

    record Item(UUID id, String kind, String title, String body, String issueKey, String actorName, Instant createdAt,
            boolean read) {
    }

    record Preference(String kind, boolean inApp, boolean email) {
    }
}
