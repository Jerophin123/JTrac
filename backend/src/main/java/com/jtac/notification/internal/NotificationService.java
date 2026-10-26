package com.jtac.notification.internal;

import com.jtac.identity.UserDirectory;
import com.jtac.identity.UserSummary;
import com.jtac.issue.IssueAccess;
import com.jtac.issue.IssueChanged;
import com.jtac.mail.EmailTemplate;
import com.jtac.mail.Mailer;
import com.jtac.organization.OrgContext;
import com.jtac.organization.OrganizationAccess;
import com.jtac.realtime.RealtimeHub;
import com.jtac.shared.Ids;
import com.jtac.shared.web.ApiErrors;
import com.jtac.shared.web.PageResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final Set<String> ALWAYS_VISIBLE = Set.of("title", "type", "status");
    private static final Map<String, String> FIELD_NAMES = Map.ofEntries(Map.entry("title", "title"),
            Map.entry("type", "type"), Map.entry("status", "status"), Map.entry("description", "description"),
            Map.entry("assignee", "assignee"), Map.entry("priority", "priority"), Map.entry("labels", "labels"),
            Map.entry("dueDate", "due date"), Map.entry("startDate", "start date"), Map.entry("estimate", "estimate"),
            Map.entry("milestone", "milestone"), Map.entry("parent", "parent"));

    private final JdbcClient jdbc;
    private final IssueAccess issues;
    private final UserDirectory users;
    private final OrganizationAccess organizations;
    private final Mailer mailer;
    private final RealtimeHub realtime;

    NotificationService(JdbcClient jdbc, IssueAccess issues, UserDirectory users, OrganizationAccess organizations,
            Mailer mailer, RealtimeHub realtime) {
        this.jdbc = jdbc;
        this.issues = issues;
        this.users = users;
        this.organizations = organizations;
        this.mailer = mailer;
        this.realtime = realtime;
    }

    record Notification(UUID id, String kind, String title, String body, String link, String issueKey,
            String actorName, Instant createdAt, boolean read) {
    }

    record Preference(String kind, boolean inApp, boolean email) {
    }

    // --- event handling ------------------------------------------------------------------

    @ApplicationModuleListener
    void on(IssueChanged event) {
        notify(event);
    }

    /** Computes and delivers notifications for one event (public for tests and replays). */
    void notify(IssueChanged event) {
        if ("DELETED".equals(event.kind())) {
            return;
        }
        Map<UUID, NotificationKind> recipients = recipients(event);
        if (recipients.isEmpty()) {
            return;
        }
        Optional<String> slug = organizations.slugOf(event.organizationId());
        if (slug.isEmpty()) {
            return;
        }
        String actorName = event.actorId() == null ? "Someone"
                : users.find(event.actorId()).map(UserSummary::displayName).orElse("Someone");
        String link = "/" + slug.get() + "/issue/" + event.issueKey();
        for (Map.Entry<UUID, NotificationKind> recipient : recipients.entrySet()) {
            Optional<Set<String>> visible = issues.visibleFieldsFor(event.organizationId(), event.issueId(), recipient.getKey());
            if (visible.isEmpty()) {
                continue; // cannot browse the issue (any more)
            }
            List<String> visibleChanges = event.fields().stream()
                    .filter(f -> ALWAYS_VISIBLE.contains(f) || visible.get().contains(f))
                    .toList();
            NotificationKind kind = recipient.getValue();
            if (kind == NotificationKind.UPDATED && visibleChanges.isEmpty()) {
                continue; // nothing this recipient may see changed
            }
            String title = title(kind, actorName, event);
            String body = body(kind, event, visibleChanges);
            Preference pref = preference(recipient.getKey(), event.organizationId(), kind);
            if (pref.inApp()) {
                jdbc.sql("""
                        INSERT INTO notification (id, organization_id, recipient_id, kind, issue_id, actor_id, title, body, link)
                        SELECT :id, :org, :recipient, :kind, :issue, :actor, :title, :body, :link
                         WHERE EXISTS (SELECT 1 FROM membership WHERE organization_id = :org AND user_id = :recipient)
                           AND EXISTS (SELECT 1 FROM issue WHERE id = :issue)
                        """)
                        .param("id", Ids.newId()).param("org", event.organizationId()).param("recipient", recipient.getKey())
                        .param("kind", kind.name()).param("issue", event.issueId()).param("actor", event.actorId())
                        .param("title", title).param("body", body).param("link", link).update();
                realtime.send(event.organizationId(), List.of(recipient.getKey()), "notification.created",
                        Map.of("issueKey", event.issueKey()));
            }
            if (pref.email()) {
                users.find(recipient.getKey()).filter(UserSummary::active).ifPresent(user -> mailer.send(
                        EmailTemplate.subject(event.issueKey() + ": " + truncate(event.title(), 120))
                                .paragraph(title)
                                .paragraph(body == null ? "" : body)
                                .action("Open " + event.issueKey(), mailer.link(link))
                                .footnote("You receive this because you are involved in " + event.issueKey()
                                        + ". Change your notification settings in JTRAC.")
                                .to(user.email())));
            }
        }
    }

    private Map<UUID, NotificationKind> recipients(IssueChanged event) {
        Map<UUID, NotificationKind> result = new LinkedHashMap<>();
        Object assignee = event.details().get("assigneeId");
        if (assignee != null) {
            result.put(UUID.fromString(assignee.toString()), NotificationKind.ASSIGNED);
        }
        Object mentioned = event.details().get("mentionedUserIds");
        if (mentioned instanceof List<?> ids) {
            ids.forEach(id -> result.putIfAbsent(UUID.fromString(id.toString()), NotificationKind.MENTIONED));
        }
        if (!"CREATED".equals(event.kind())) {
            NotificationKind watcherKind = switch (event.kind()) {
                case "COMMENTED" -> NotificationKind.COMMENTED;
                case "TRANSITIONED" -> NotificationKind.STATUS_CHANGED;
                default -> NotificationKind.UPDATED;
            };
            for (UUID watcher : issues.watchers(event.issueId())) {
                result.putIfAbsent(watcher, watcherKind);
            }
        }
        if (event.actorId() != null) {
            result.remove(event.actorId());
        }
        return result;
    }

    private static String title(NotificationKind kind, String actor, IssueChanged event) {
        return switch (kind) {
            case ASSIGNED -> actor + " assigned " + event.issueKey() + " to you";
            case MENTIONED -> actor + " mentioned you on " + event.issueKey();
            case COMMENTED -> actor + " commented on " + event.issueKey();
            case STATUS_CHANGED -> actor + " moved " + event.issueKey() + " to " + event.details().getOrDefault("toStatus", "a new status");
            case UPDATED -> actor + " updated " + event.issueKey();
        };
    }

    private static String body(NotificationKind kind, IssueChanged event, List<String> visibleChanges) {
        if (kind == NotificationKind.MENTIONED || kind == NotificationKind.COMMENTED) {
            Object excerpt = event.details().get("excerpt");
            return truncate(event.title(), 200) + (excerpt == null ? "" : ": “" + excerpt + "”");
        }
        List<String> names = new ArrayList<>();
        for (String field : visibleChanges) {
            names.add(field.startsWith("cf:") ? field.substring(3).replace('_', ' ') : FIELD_NAMES.getOrDefault(field, field));
        }
        return truncate(event.title(), 200) + (names.isEmpty() ? "" : " (changed: " + String.join(", ", new LinkedHashSet<>(names)) + ")");
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    // --- inbox and preferences -----------------------------------------------------------

    @Transactional(readOnly = true)
    PageResponse<Notification> inbox(OrgContext ctx, boolean unreadOnly, int page, int size) {
        String where = " WHERE n.organization_id = :org AND n.recipient_id = :user" + (unreadOnly ? " AND n.read_at IS NULL" : "");
        long total = jdbc.sql("SELECT count(*) FROM notification n" + where)
                .param("org", ctx.organizationId()).param("user", ctx.actorId()).query(Long.class).single();
        List<Notification> items = jdbc.sql("""
                SELECT n.id, n.kind, n.title, n.body, n.link, p.key || '-' || i.number AS issue_key, u.display_name,
                       n.created_at, n.read_at IS NOT NULL AS is_read
                  FROM notification n
                  LEFT JOIN issue i ON i.id = n.issue_id LEFT JOIN project p ON p.id = i.project_id
                  LEFT JOIN user_account u ON u.id = n.actor_id
                """ + where + " ORDER BY n.created_at DESC, n.id LIMIT :limit OFFSET :offset")
                .param("org", ctx.organizationId()).param("user", ctx.actorId())
                .param("limit", size).param("offset", (long) page * size)
                .query((rs, n) -> new Notification(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("title"),
                        rs.getString("body"), rs.getString("link"), rs.getString("issue_key"), rs.getString("display_name"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getBoolean("is_read")))
                .list();
        return new PageResponse<>(items, page, size, total);
    }

    @Transactional(readOnly = true)
    long unreadCount(OrgContext ctx) {
        return jdbc.sql("SELECT count(*) FROM notification WHERE organization_id = :org AND recipient_id = :user AND read_at IS NULL")
                .param("org", ctx.organizationId()).param("user", ctx.actorId()).query(Long.class).single();
    }

    @Transactional
    void markRead(OrgContext ctx, UUID notificationId) {
        int updated = jdbc.sql("""
                UPDATE notification SET read_at = coalesce(read_at, now())
                 WHERE id = :id AND organization_id = :org AND recipient_id = :user
                """)
                .param("id", notificationId).param("org", ctx.organizationId()).param("user", ctx.actorId()).update();
        if (updated == 0) {
            throw ApiErrors.notFound("Notification");
        }
    }

    @Transactional
    int markAllRead(OrgContext ctx) {
        return jdbc.sql("UPDATE notification SET read_at = now() WHERE organization_id = :org AND recipient_id = :user AND read_at IS NULL")
                .param("org", ctx.organizationId()).param("user", ctx.actorId()).update();
    }

    @Transactional(readOnly = true)
    List<Preference> preferences(OrgContext ctx) {
        Map<NotificationKind, Preference> stored = storedPreferences(ctx.actorId(), ctx.organizationId());
        List<Preference> result = new ArrayList<>();
        for (NotificationKind kind : NotificationKind.values()) {
            result.add(stored.getOrDefault(kind, new Preference(kind.name(), true, kind.emailByDefault())));
        }
        return result;
    }

    @Transactional
    List<Preference> updatePreferences(OrgContext ctx, List<Preference> preferences) {
        for (Preference preference : preferences) {
            NotificationKind kind;
            try {
                kind = NotificationKind.valueOf(preference.kind());
            } catch (IllegalArgumentException | NullPointerException e) {
                throw ApiErrors.invalid("kind", "Unknown notification kind.");
            }
            jdbc.sql("""
                    INSERT INTO notification_preference (user_id, organization_id, kind, in_app, email)
                    VALUES (:user, :org, :kind, :inApp, :email)
                    ON CONFLICT (user_id, organization_id, kind) DO UPDATE SET in_app = EXCLUDED.in_app, email = EXCLUDED.email
                    """)
                    .param("user", ctx.actorId()).param("org", ctx.organizationId()).param("kind", kind.name())
                    .param("inApp", preference.inApp()).param("email", preference.email()).update();
        }
        return preferences(ctx);
    }

    private Preference preference(UUID userId, UUID organizationId, NotificationKind kind) {
        return storedPreferences(userId, organizationId).getOrDefault(kind, new Preference(kind.name(), true, kind.emailByDefault()));
    }

    private Map<NotificationKind, Preference> storedPreferences(UUID userId, UUID organizationId) {
        Map<NotificationKind, Preference> result = new EnumMap<>(NotificationKind.class);
        jdbc.sql("SELECT kind, in_app, email FROM notification_preference WHERE user_id = :user AND organization_id = :org")
                .param("user", userId).param("org", organizationId)
                .query((rs, n) -> new Preference(rs.getString("kind"), rs.getBoolean("in_app"), rs.getBoolean("email")))
                .list().forEach(p -> {
                    try {
                        result.put(NotificationKind.valueOf(p.kind()), p);
                    } catch (IllegalArgumentException e) {
                        log.debug("Ignoring obsolete notification kind {}", p.kind());
                    }
                });
        return result;
    }
}
