package com.jtac.integration.internal;

import com.jtac.audit.AuditEntry;
import com.jtac.audit.AuditLog;
import com.jtac.identity.UserDirectory;
import com.jtac.identity.UserSummary;
import com.jtac.issue.IssueAccess;
import com.jtac.issue.IssueChanged;
import com.jtac.organization.OrgContext;
import com.jtac.organization.OrganizationAccess;
import com.jtac.project.ProjectAccess;
import com.jtac.project.ProjectContext;
import com.jtac.shared.Ids;
import com.jtac.shared.Tokens;
import com.jtac.shared.web.ApiErrors;
import com.jtac.shared.web.PageResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Webhook administration (organization admins) and event fan-out into the delivery queue. */
@Service
class WebhookService {

    static final Map<String, String> EVENT_TYPES = Map.of(
            "CREATED", "issue.created",
            "UPDATED", "issue.updated",
            "TRANSITIONED", "issue.transitioned",
            "DELETED", "issue.deleted",
            "COMMENTED", "issue.commented",
            "LINKED", "issue.linked",
            "ATTACHMENT_ADDED", "issue.attachment_added");
    static final Set<String> SUBSCRIBABLE = Set.copyOf(EVENT_TYPES.values());
    private static final int MAX_WEBHOOKS = 50;
    private static final Set<String> ALWAYS_VISIBLE = Set.of("title", "type", "status");

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final SecretBox secrets;
    private final WebhookTargets targets;
    private final ProjectAccess projects;
    private final IssueAccess issues;
    private final OrganizationAccess organizations;
    private final UserDirectory users;
    private final AuditLog audit;

    WebhookService(JdbcClient jdbc, JsonMapper json, SecretBox secrets, WebhookTargets targets, ProjectAccess projects,
            IssueAccess issues, OrganizationAccess organizations, UserDirectory users, AuditLog audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.secrets = secrets;
        this.targets = targets;
        this.projects = projects;
        this.issues = issues;
        this.organizations = organizations;
        this.users = users;
        this.audit = audit;
    }

    record Webhook(UUID id, String url, String description, String projectKey, List<String> events, boolean active,
            String createdByName, Instant createdAt, Instant updatedAt, long version, DeliveryStats stats) {
    }

    record DeliveryStats(long pending, long succeeded, long failed, Instant lastDeliveryAt) {
    }

    /** Returned once on create and rotation; the secret is never readable afterwards. */
    record WebhookWithSecret(Webhook webhook, String secret) {
    }

    record WebhookInput(String url, String description, String projectKey, List<String> events, Boolean active) {
    }

    record Delivery(UUID id, String eventType, UUID eventId, String status, int attempts, Integer lastStatusCode,
            String lastError, Integer lastDurationMs, Instant createdAt, Instant nextAttemptAt, Instant deliveredAt,
            Map<String, Object> payload) {
    }

    // --- administration ------------------------------------------------------------------

    @Transactional(readOnly = true)
    List<Webhook> list(OrgContext ctx) {
        ctx.requireAdmin();
        return jdbc.sql(SELECT + " WHERE w.organization_id = :org ORDER BY w.created_at, w.id")
                .param("org", ctx.organizationId()).query(this::webhook).list();
    }

    @Transactional(readOnly = true)
    Webhook get(OrgContext ctx, UUID id) {
        ctx.requireAdmin();
        return load(ctx, id);
    }

    @Transactional
    WebhookWithSecret create(OrgContext ctx, WebhookInput input) {
        ctx.requireAdmin();
        Validated v = validate(ctx, input, true);
        long count = jdbc.sql("SELECT count(*) FROM webhook WHERE organization_id = :org")
                .param("org", ctx.organizationId()).query(Long.class).single();
        if (count >= MAX_WEBHOOKS) {
            throw ApiErrors.conflict("An organization can have at most " + MAX_WEBHOOKS + " webhooks.");
        }
        UUID id = Ids.newId();
        String secret = "whsec_" + Tokens.newToken();
        jdbc.sql("""
                INSERT INTO webhook (id, organization_id, project_id, url, description, secret_ciphertext, events, active, created_by)
                VALUES (:id, :org, :project, :url, :description, :secret, :events, :active, :actor)
                """)
                .param("id", id).param("org", ctx.organizationId()).param("project", v.projectId()).param("url", v.url())
                .param("description", v.description()).param("secret", secrets.encrypt(secret, id.toString()))
                .param("events", v.events().toArray(String[]::new)).param("active", v.active())
                .param("actor", ctx.actorId()).update();
        audit.record(AuditEntry.of(ctx.organizationId(), ctx.actorId(), "webhook.created").target("webhook", id)
                .detail("url", v.url()).detail("events", v.events()));
        return new WebhookWithSecret(load(ctx, id), secret);
    }

    @Transactional
    Webhook update(OrgContext ctx, UUID id, long version, WebhookInput input) {
        ctx.requireAdmin();
        load(ctx, id);
        Validated v = validate(ctx, input, false);
        int updated = jdbc.sql("""
                UPDATE webhook SET url = :url, description = :description, project_id = :project, events = :events,
                       active = :active, updated_at = now(), version = version + 1
                 WHERE id = :id AND organization_id = :org AND version = :version
                """)
                .param("url", v.url()).param("description", v.description()).param("project", v.projectId())
                .param("events", v.events().toArray(String[]::new)).param("active", v.active())
                .param("id", id).param("org", ctx.organizationId()).param("version", version).update();
        if (updated == 0) {
            throw new OptimisticLockingFailureException("Webhook was changed by someone else.");
        }
        audit.record(AuditEntry.of(ctx.organizationId(), ctx.actorId(), "webhook.updated").target("webhook", id)
                .detail("url", v.url()).detail("events", v.events()).detail("active", v.active()));
        return load(ctx, id);
    }

    @Transactional
    void delete(OrgContext ctx, UUID id) {
        ctx.requireAdmin();
        load(ctx, id);
        jdbc.sql("DELETE FROM webhook WHERE id = :id AND organization_id = :org")
                .param("id", id).param("org", ctx.organizationId()).update();
        audit.record(AuditEntry.of(ctx.organizationId(), ctx.actorId(), "webhook.deleted").target("webhook", id));
    }

    @Transactional
    WebhookWithSecret rotateSecret(OrgContext ctx, UUID id) {
        ctx.requireAdmin();
        load(ctx, id);
        String secret = "whsec_" + Tokens.newToken();
        jdbc.sql("UPDATE webhook SET secret_ciphertext = :secret, updated_at = now(), version = version + 1 WHERE id = :id AND organization_id = :org")
                .param("secret", secrets.encrypt(secret, id.toString())).param("id", id).param("org", ctx.organizationId())
                .update();
        audit.record(AuditEntry.of(ctx.organizationId(), ctx.actorId(), "webhook.secret_rotated").target("webhook", id));
        return new WebhookWithSecret(load(ctx, id), secret);
    }

    /** Queues a {@code ping} delivery to test the endpoint. */
    @Transactional
    UUID ping(OrgContext ctx, UUID id) {
        ctx.requireAdmin();
        load(ctx, id);
        UUID eventId = Ids.newId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", eventId.toString());
        payload.put("type", "ping");
        payload.put("occurredAt", Instant.now().toString());
        payload.put("organization", Map.of("slug", ctx.slug()));
        payload.put("webhookId", id.toString());
        return enqueue(ctx.organizationId(), id, eventId, "ping", payload);
    }

    @Transactional(readOnly = true)
    PageResponse<Delivery> deliveries(OrgContext ctx, UUID webhookId, int page, int size) {
        ctx.requireAdmin();
        load(ctx, webhookId);
        long total = jdbc.sql("SELECT count(*) FROM webhook_delivery WHERE webhook_id = :id AND organization_id = :org")
                .param("id", webhookId).param("org", ctx.organizationId()).query(Long.class).single();
        List<Delivery> items = jdbc.sql("""
                SELECT * FROM webhook_delivery WHERE webhook_id = :id AND organization_id = :org
                 ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset
                """)
                .param("id", webhookId).param("org", ctx.organizationId()).param("limit", size)
                .param("offset", (long) page * size)
                .query((rs, n) -> delivery(rs, false)).list();
        return new PageResponse<>(items, page, size, total);
    }

    @Transactional(readOnly = true)
    Delivery delivery(OrgContext ctx, UUID webhookId, UUID deliveryId) {
        ctx.requireAdmin();
        return jdbc.sql("SELECT * FROM webhook_delivery WHERE id = :id AND webhook_id = :webhook AND organization_id = :org")
                .param("id", deliveryId).param("webhook", webhookId).param("org", ctx.organizationId())
                .query((rs, n) -> delivery(rs, true)).optional().orElseThrow(() -> ApiErrors.notFound("Delivery"));
    }

    /** Sends a delivery again from scratch (fresh attempt budget, same event id and payload). */
    @Transactional
    Delivery redeliver(OrgContext ctx, UUID webhookId, UUID deliveryId) {
        ctx.requireAdmin();
        int updated = jdbc.sql("""
                UPDATE webhook_delivery SET status = 'PENDING', attempts = 0, next_attempt_at = now(), delivered_at = NULL
                 WHERE id = :id AND webhook_id = :webhook AND organization_id = :org AND status <> 'PENDING'
                """)
                .param("id", deliveryId).param("webhook", webhookId).param("org", ctx.organizationId()).update();
        if (updated == 0) {
            Delivery existing = delivery(ctx, webhookId, deliveryId);
            if ("PENDING".equals(existing.status())) {
                throw ApiErrors.conflict("This delivery is already queued.");
            }
        }
        audit.record(AuditEntry.of(ctx.organizationId(), ctx.actorId(), "webhook.redelivered").target("webhook", webhookId)
                .detail("deliveryId", deliveryId));
        return delivery(ctx, webhookId, deliveryId);
    }

    // --- event fan-out ---------------------------------------------------------------------

    @ApplicationModuleListener
    void on(IssueChanged event) {
        fanOut(event);
    }

    /** Queues one delivery per matching webhook whose creator may see the event. */
    void fanOut(IssueChanged event) {
        String type = EVENT_TYPES.get(event.kind());
        if (type == null) {
            return;
        }
        List<Target> hooks = jdbc.sql("""
                SELECT id, created_by FROM webhook
                 WHERE organization_id = :org AND active AND :type = ANY (events)
                   AND (project_id IS NULL OR project_id = :project)
                """)
                .param("org", event.organizationId()).param("type", type).param("project", event.projectId())
                .query((rs, n) -> new Target(rs.getObject("id", UUID.class), rs.getObject("created_by", UUID.class)))
                .list();
        if (hooks.isEmpty()) {
            return;
        }
        Optional<String> slug = organizations.slugOf(event.organizationId());
        if (slug.isEmpty()) {
            return;
        }
        for (Target hook : hooks) {
            if (hook.creatorId() == null || organizations.roleOf(event.organizationId(), hook.creatorId()).isEmpty()) {
                continue; // the creator left: the webhook no longer has anyone's permissions to act with
            }
            List<String> fields;
            if ("DELETED".equals(event.kind())) {
                if (projects.grantsFor(event.organizationId(), event.projectId(), hook.creatorId()).isEmpty()) {
                    continue;
                }
                fields = List.of();
            } else {
                Optional<Set<String>> visible = issues.visibleFieldsFor(event.organizationId(), event.issueId(), hook.creatorId());
                if (visible.isEmpty()) {
                    continue;
                }
                fields = event.fields().stream().filter(f -> ALWAYS_VISIBLE.contains(f) || visible.get().contains(f)).toList();
            }
            enqueue(event.organizationId(), hook.id(), event.eventId(), type, payload(event, type, slug.get(), fields));
        }
    }

    private Map<String, Object> payload(IssueChanged event, String type, String slug, List<String> fields) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", event.eventId().toString());
        payload.put("type", type);
        payload.put("occurredAt", event.occurredAt().toString());
        payload.put("organization", Map.of("slug", slug));
        String projectKey = event.issueKey().substring(0, event.issueKey().lastIndexOf('-'));
        payload.put("project", Map.of("key", projectKey));
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("key", event.issueKey());
        issue.put("title", event.title());
        if (!"DELETED".equals(event.kind())) {
            issue.put("apiUrl", "/api/v1/orgs/" + slug + "/issues/" + event.issueKey());
        }
        payload.put("issue", issue);
        if (event.actorId() != null) {
            users.find(event.actorId()).ifPresent((UserSummary actor) -> payload.put("actor",
                    Map.of("id", actor.id().toString(), "handle", actor.handle(), "displayName", actor.displayName())));
        }
        payload.put("changedFields", new ArrayList<>(new LinkedHashSet<>(fields)));
        if (event.details().get("commentId") != null) {
            payload.put("commentId", event.details().get("commentId").toString());
        }
        return payload;
    }

    private UUID enqueue(UUID organizationId, UUID webhookId, UUID eventId, String type, Map<String, Object> payload) {
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO webhook_delivery (id, organization_id, webhook_id, event_id, event_type, payload)
                VALUES (:id, :org, :webhook, :event, :type, CAST(:payload AS jsonb))
                ON CONFLICT (webhook_id, event_id) DO NOTHING
                """)
                .param("id", id).param("org", organizationId).param("webhook", webhookId).param("event", eventId)
                .param("type", type).param("payload", json.writeValueAsString(payload)).update();
        return id;
    }

    private record Target(UUID id, UUID creatorId) {
    }

    // --- helpers ---------------------------------------------------------------------------

    private record Validated(String url, String description, UUID projectId, List<String> events, boolean active) {
    }

    private Validated validate(OrgContext ctx, WebhookInput input, boolean creating) {
        String url = targets.validate(input.url()).toString();
        if (url.length() > 2000) {
            throw ApiErrors.invalid("url", "At most 2000 characters.");
        }
        String description = input.description() == null || input.description().isBlank() ? null : input.description().strip();
        if (description != null && description.length() > 200) {
            throw ApiErrors.invalid("description", "At most 200 characters.");
        }
        UUID projectId = null;
        if (input.projectKey() != null && !input.projectKey().isBlank()) {
            ProjectContext project = projects.require(ctx, input.projectKey());
            projectId = project.projectId();
        }
        if (input.events() == null || input.events().isEmpty()) {
            throw ApiErrors.invalid("events", "Choose at least one event.");
        }
        List<String> events = new ArrayList<>(new LinkedHashSet<>(input.events()));
        for (String event : events) {
            if (!SUBSCRIBABLE.contains(event)) {
                throw ApiErrors.invalid("events", "Unknown event " + event + ".");
            }
        }
        boolean active = input.active() == null ? creating : input.active();
        return new Validated(url, description, projectId, events, active);
    }

    private static final String SELECT = """
            SELECT w.id, w.url, w.description, p.key AS project_key, w.events, w.active, u.display_name AS created_by_name,
                   w.created_at, w.updated_at, w.version,
                   (SELECT count(*) FROM webhook_delivery d WHERE d.webhook_id = w.id AND d.status = 'PENDING') AS pending,
                   (SELECT count(*) FROM webhook_delivery d WHERE d.webhook_id = w.id AND d.status = 'SUCCEEDED') AS succeeded,
                   (SELECT count(*) FROM webhook_delivery d WHERE d.webhook_id = w.id AND d.status = 'FAILED') AS failed,
                   (SELECT max(d.delivered_at) FROM webhook_delivery d WHERE d.webhook_id = w.id) AS last_delivery_at
              FROM webhook w
              LEFT JOIN project p ON p.id = w.project_id
              LEFT JOIN user_account u ON u.id = w.created_by
            """;

    private Webhook load(OrgContext ctx, UUID id) {
        return jdbc.sql(SELECT + " WHERE w.id = :id AND w.organization_id = :org")
                .param("id", id).param("org", ctx.organizationId())
                .query(this::webhook).optional().orElseThrow(() -> ApiErrors.notFound("Webhook"));
    }

    private Webhook webhook(ResultSet rs, int n) throws SQLException {
        OffsetDateTime last = rs.getObject("last_delivery_at", OffsetDateTime.class);
        return new Webhook(rs.getObject("id", UUID.class), rs.getString("url"), rs.getString("description"),
                rs.getString("project_key"), List.of((String[]) rs.getArray("events").getArray()), rs.getBoolean("active"),
                rs.getString("created_by_name"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(), rs.getLong("version"),
                new DeliveryStats(rs.getLong("pending"), rs.getLong("succeeded"), rs.getLong("failed"),
                        last == null ? null : last.toInstant()));
    }

    @SuppressWarnings("unchecked")
    private Delivery delivery(ResultSet rs, boolean withPayload) throws SQLException {
        Integer code = (Integer) rs.getObject("last_status_code");
        Integer duration = (Integer) rs.getObject("last_duration_ms");
        OffsetDateTime delivered = rs.getObject("delivered_at", OffsetDateTime.class);
        return new Delivery(rs.getObject("id", UUID.class), rs.getString("event_type"), rs.getObject("event_id", UUID.class),
                rs.getString("status"), rs.getInt("attempts"), code, rs.getString("last_error"), duration,
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("next_attempt_at", OffsetDateTime.class).toInstant(),
                delivered == null ? null : delivered.toInstant(),
                withPayload ? json.readValue(rs.getString("payload"), Map.class) : null);
    }
}
