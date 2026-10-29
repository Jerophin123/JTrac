package com.jtac.notification.internal;

import com.jtac.notification.Inbox;
import com.jtac.organization.OrgContext;
import com.jtac.shared.web.PageResponse;
import java.util.List;
import org.springframework.stereotype.Component;

/** {@link Inbox} on top of {@link NotificationService}. */
@Component
class InboxAdapter implements Inbox {

    private final NotificationService notifications;

    InboxAdapter(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Override
    public PageResponse<Item> page(OrgContext org, boolean unreadOnly, int page, int size) {
        return notifications.inbox(org, unreadOnly, page, size).map(n -> new Item(n.id(), n.kind(), n.title(), n.body(),
                n.issueKey(), n.actorName(), n.createdAt(), n.read()));
    }

    @Override
    public List<Preference> preferences(OrgContext org) {
        return notifications.preferences(org).stream().map(p -> new Preference(p.kind(), p.inApp(), p.email())).toList();
    }
}
