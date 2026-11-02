package com.jtac.utcp.internal;

import com.jtac.identity.CurrentUser;
import com.jtac.organization.OrgContext;
import com.jtac.organization.OrganizationAccess;
import com.jtac.shared.Ids;
import com.jtac.shared.web.ApiErrors;
import com.jtac.shared.web.PageResponse;
import com.jtac.shared.web.RateLimiter;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service managing the native Universal Tool Calling Protocol (UTCP) provider lifecycle,
 * manual generation, and guarded tool execution.
 */
@Service
class UtcpService {

    private static final Logger log = LoggerFactory.getLogger(UtcpService.class);

    enum LifecycleState {
        DISABLED,
        CONFIGURING,
        ENABLED,
        CONNECTED,
        ERROR
    }

    record UtcpSettingsView(
            boolean serverEnabled,
            boolean enabled,
            LifecycleState state,
            String manualUrl,
            String providerName,
            String utcpVersion,
            String manualVersion,
            Instant lastUsedAt,
            String lastClient,
            int toolCount
    ) {}

    record ActivityView(
            UUID id,
            Instant occurredAt,
            String toolName,
            String outcome,
            String target,
            String client,
            Integer durationMs
    ) {}

    private final UtcpProperties properties;
    private final UtcpUrls urls;
    private final UtcpToolCatalogue catalogue;
    private final UtcpActivityLog activityLog;
    private final OrganizationAccess organizations;
    private final JdbcClient jdbc;
    private final RateLimiter perUser;

    UtcpService(
            UtcpProperties properties,
            UtcpUrls urls,
            UtcpToolCatalogue catalogue,
            UtcpActivityLog activityLog,
            OrganizationAccess organizations,
            JdbcClient jdbc
    ) {
        this.properties = properties;
        this.urls = urls;
        this.catalogue = catalogue;
        this.activityLog = activityLog;
        this.organizations = organizations;
        this.jdbc = jdbc;
        this.perUser = new RateLimiter("UTCP", properties.requestsPerMinute(), Duration.ofMinutes(1), 50000);
    }

    UtcpManual.Manual getManual(String baseUrlOverride) {
        if (!properties.enabled()) {
            throw ApiErrors.conflict("UTCP integration is disabled by server administrator.");
        }

        UtcpManual.AuthConfig authConfig = new UtcpManual.AuthConfig(
                "api_key",
                "Bearer ${JTRAC_API_TOKEN}",
                "Authorization",
                "header"
        );

        List<UtcpManual.Tool> manualTools = catalogue.tools().stream().map(t -> {
            UtcpManual.ToolCallTemplate template = new UtcpManual.ToolCallTemplate(
                    "jtrac",
                    "http",
                    urls.toolUrl(t.name(), baseUrlOverride),
                    "POST",
                    Map.of("Content-Type", "application/json"),
                    authConfig
            );
            return new UtcpManual.Tool(
                    t.name(),
                    t.description(),
                    t.inputSchema(),
                    t.outputSchema(),
                    List.of("jtrac", t.category().toLowerCase(java.util.Locale.ROOT)),
                    template
            );
        }).toList();

        return new UtcpManual.Manual(
                UtcpManual.SPEC_VERSION,
                UtcpManual.MANUAL_VERSION,
                manualTools
        );
    }

    UtcpManual.Manual getManual() {
        return getManual(null);
    }

    List<UtcpToolDef> listTools() {
        return catalogue.tools();
    }

    UtcpToolDef getTool(String name) {
        String resolvedName = resolveToolAlias(name);
        return catalogue.tools().stream()
                .filter(t -> t.name().equalsIgnoreCase(resolvedName))
                .findFirst()
                .orElseThrow(() -> ApiErrors.notFound("Unknown UTCP tool '" + name + "'."));
    }

    private static String resolveToolAlias(String name) {
        if (name == null) {
            return "";
        }
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "get_issue_details" -> "get_issue";
            case "list_accessible_projects" -> "list_projects";
            case "get_project_details" -> "get_project";
            case "get_workflow_information" -> "get_workflow";
            case "get_available_issue_statuses" -> "get_available_statuses";
            case "get_authorized_user_info" -> "get_user_info";
            case "get_report_data" -> "list_fix_reports";
            default -> name;
        };
    }

    Object execute(CurrentUser user, String orgSlug, String toolName, Map<String, Object> arguments, String clientName) {
        if (!properties.enabled()) {
            throw ApiErrors.conflict("UTCP integration is disabled on this server.");
        }

        UtcpToolDef tool = getTool(toolName);
        OrgContext org = resolveOrg(user, orgSlug);

        // Check user/organization enablement
        boolean isEnabled = isIntegrationEnabled(user.id(), org.organizationId());
        if (!isEnabled) {
            activityLog.record(user.id(), org.organizationId(), toolName, UtcpActivityLog.Outcome.DENIED,
                    extractTarget(arguments), clientName, 0);
            throw ApiErrors.forbidden("UTCP integration is disabled for this organization. Enable it under Settings.");
        }

        // Rate limiting
        Optional<Duration> wait = perUser.tryAcquire(user.id().toString());
        if (wait.isPresent()) {
            activityLog.record(user.id(), org.organizationId(), toolName, UtcpActivityLog.Outcome.RATE_LIMITED,
                    extractTarget(arguments), clientName, 0);
            throw new RateLimiter.RateLimited("UTCP", wait.get());
        }

        UtcpContext ctx = new UtcpContext(user, org, clientName, UUID.randomUUID().toString());
        long start = System.nanoTime();
        UtcpActivityLog.Outcome outcome = UtcpActivityLog.Outcome.OK;
        String target = extractTarget(arguments);

        try {
            Object result = tool.handler().handle(ctx, arguments != null ? arguments : Map.of());
            return result;
        } catch (ApiErrors.ValidationFailed | IllegalArgumentException e) {
            outcome = UtcpActivityLog.Outcome.ERROR;
            throw e;
        } catch (ApiErrors.Forbidden | ApiErrors.NotFound e) {
            outcome = UtcpActivityLog.Outcome.DENIED;
            throw e;
        } catch (Exception e) {
            outcome = UtcpActivityLog.Outcome.ERROR;
            log.error("UTCP tool execution error for {}: {}", toolName, e.getMessage(), e);
            throw ApiErrors.badRequest("Tool execution failed: " + e.getMessage());
        } finally {
            int durationMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - start) / 1_000_000);
            activityLog.record(user.id(), org.organizationId(), toolName, outcome, target, clientName, durationMs);
        }
    }

    UtcpSettingsView getSettings(CurrentUser user, UUID organizationId) {
        UUID orgId = organizationId != null ? organizationId : user.tokenOrganizationId();
        if (orgId == null) {
            // Find default org
            orgId = jdbc.sql("SELECT organization_id FROM membership WHERE user_id = :u ORDER BY joined_at LIMIT 1")
                    .param("u", user.id())
                    .query((rs, n) -> rs.getObject("organization_id", UUID.class))
                    .optional()
                    .orElse(null);
        }

        if (orgId == null) {
            return new UtcpSettingsView(
                    properties.enabled(),
                    false,
                    LifecycleState.DISABLED,
                    urls.manualUrl(),
                    "JTRAC",
                    UtcpManual.SPEC_VERSION,
                    UtcpManual.MANUAL_VERSION,
                    null,
                    null,
                    catalogue.tools().size()
            );
        }

        record SettingRow(boolean enabled, Instant lastUsedAt, String lastClient) {}

        Optional<SettingRow> row = jdbc.sql("""
                SELECT enabled, last_used_at, last_client
                  FROM utcp_setting
                 WHERE user_id = :u AND organization_id = :org
                """)
                .param("u", user.id())
                .param("org", orgId)
                .query((rs, n) -> new SettingRow(
                        rs.getBoolean("enabled"),
                        rs.getObject("last_used_at", OffsetDateTime.class) != null
                                ? rs.getObject("last_used_at", OffsetDateTime.class).toInstant() : null,
                        rs.getString("last_client")
                ))
                .optional();

        boolean enabled = row.map(SettingRow::enabled).orElse(true);
        Instant lastUsed = row.flatMap(r -> Optional.ofNullable(r.lastUsedAt())).orElse(null);
        String lastClient = row.flatMap(r -> Optional.ofNullable(r.lastClient())).orElse(null);

        LifecycleState state;
        if (!properties.enabled()) {
            state = LifecycleState.ERROR;
        } else if (!enabled) {
            state = LifecycleState.DISABLED;
        } else if (lastUsed != null) {
            state = LifecycleState.CONNECTED;
        } else {
            state = LifecycleState.ENABLED;
        }

        return new UtcpSettingsView(
                properties.enabled(),
                enabled,
                state,
                urls.manualUrl(),
                "JTRAC",
                UtcpManual.SPEC_VERSION,
                UtcpManual.MANUAL_VERSION,
                lastUsed,
                lastClient,
                catalogue.tools().size()
        );
    }

    @Transactional
    void enable(CurrentUser user, UUID organizationId) {
        UUID orgId = requireOrgId(user, organizationId);
        jdbc.sql("""
                INSERT INTO utcp_setting (id, user_id, organization_id, enabled, updated_at)
                VALUES (:id, :u, :org, true, now())
                ON CONFLICT (user_id, organization_id)
                DO UPDATE SET enabled = true, updated_at = now()
                """)
                .param("id", Ids.newId())
                .param("u", user.id())
                .param("org", orgId)
                .update();
    }

    @Transactional
    void disable(CurrentUser user, UUID organizationId) {
        UUID orgId = requireOrgId(user, organizationId);
        jdbc.sql("""
                INSERT INTO utcp_setting (id, user_id, organization_id, enabled, updated_at)
                VALUES (:id, :u, :org, false, now())
                ON CONFLICT (user_id, organization_id)
                DO UPDATE SET enabled = false, updated_at = now()
                """)
                .param("id", Ids.newId())
                .param("u", user.id())
                .param("org", orgId)
                .update();
    }

    PageResponse<ActivityView> activity(CurrentUser user, UUID organizationId, int page, int size) {
        UUID orgId = requireOrgId(user, organizationId);
        int clampedPage = PageResponse.clampPage(page);
        int clampedSize = PageResponse.clampSize(size, 50);

        long total = jdbc.sql("SELECT count(*) FROM utcp_activity WHERE user_id = :u AND organization_id = :org")
                .param("u", user.id())
                .param("org", orgId)
                .query((rs, n) -> rs.getLong(1))
                .single();

        List<ActivityView> items = jdbc.sql("""
                SELECT id, occurred_at, tool_name, outcome, target, client, duration_ms
                  FROM utcp_activity
                 WHERE user_id = :u AND organization_id = :org
                 ORDER BY occurred_at DESC
                 LIMIT :limit OFFSET :offset
                """)
                .param("u", user.id())
                .param("org", orgId)
                .param("limit", clampedSize)
                .param("offset", clampedPage * clampedSize)
                .query((rs, n) -> new ActivityView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        rs.getString("tool_name"),
                        rs.getString("outcome"),
                        rs.getString("target"),
                        rs.getString("client"),
                        rs.getObject("duration_ms", Integer.class)
                ))
                .list();

        return new PageResponse<>(items, clampedPage, clampedSize, total);
    }

    OrgContext resolveOrg(CurrentUser user, String orgSlug) {
        if (orgSlug != null && !orgSlug.isBlank()) {
            return organizations.require(user, orgSlug);
        }
        if (user.tokenOrganizationId() != null) {
            String slug = organizations.slugOf(user.tokenOrganizationId())
                    .orElseThrow(() -> ApiErrors.notFound("Token organization not found."));
            return organizations.require(user, slug);
        }
        // First visible org
        String firstSlug = jdbc.sql("""
                SELECT o.slug FROM organization o
                  JOIN membership m ON m.organization_id = o.id
                 WHERE m.user_id = :u
                 ORDER BY m.joined_at LIMIT 1
                """)
                .param("u", user.id())
                .query((rs, n) -> rs.getString("slug"))
                .optional()
                .orElseThrow(() -> ApiErrors.forbidden("User is not a member of any organization."));

        return organizations.require(user, firstSlug);
    }

    private boolean isIntegrationEnabled(UUID userId, UUID organizationId) {
        return jdbc.sql("SELECT enabled FROM utcp_setting WHERE user_id = :u AND organization_id = :org")
                .param("u", userId)
                .param("org", organizationId)
                .query((rs, n) -> rs.getBoolean("enabled"))
                .optional()
                .orElse(true);
    }

    private UUID requireOrgId(CurrentUser user, UUID organizationId) {
        if (organizationId != null) {
            if (organizations.roleOf(organizationId, user.id()).isEmpty()) {
                throw ApiErrors.forbidden("Not a member of this organization.");
            }
            return organizationId;
        }
        if (user.tokenOrganizationId() != null) {
            return user.tokenOrganizationId();
        }
        return jdbc.sql("SELECT organization_id FROM membership WHERE user_id = :u ORDER BY joined_at LIMIT 1")
                .param("u", user.id())
                .query((rs, n) -> rs.getObject("organization_id", UUID.class))
                .optional()
                .orElseThrow(() -> ApiErrors.forbidden("No organization found."));
    }

    private static String extractTarget(Map<String, Object> arguments) {
        if (arguments == null) {
            return null;
        }
        if (arguments.containsKey("issueKey")) {
            return String.valueOf(arguments.get("issueKey"));
        }
        if (arguments.containsKey("projectKey")) {
            return String.valueOf(arguments.get("projectKey"));
        }
        if (arguments.containsKey("teamId")) {
            return String.valueOf(arguments.get("teamId"));
        }
        return null;
    }
}
