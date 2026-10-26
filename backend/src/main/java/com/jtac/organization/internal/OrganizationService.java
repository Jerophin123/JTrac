package com.jtac.organization.internal;

import com.jtac.audit.AuditEntry;
import com.jtac.audit.AuditLog;
import com.jtac.identity.CurrentUser;
import com.jtac.identity.UserDirectory;
import com.jtac.organization.OrgContext;
import com.jtac.organization.OrgRole;
import com.jtac.organization.OrganizationAccess;
import com.jtac.organization.OrganizationSettings;
import com.jtac.shared.Ids;
import com.jtac.shared.web.ApiErrors;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
class OrganizationService implements OrganizationAccess {

    static final Pattern SLUG = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?$");
    private static final Set<String> RESERVED_SLUGS = Set.of("api", "app", "admin", "login", "logout", "settings",
            "new", "orgs", "public", "static", "assets", "invite", "verify-email", "reset-password", "register",
            "forgot-password", "account", "help", "setup", "welcome");

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final AuditLog audit;
    private final UserDirectory users;

    OrganizationService(JdbcClient jdbc, JsonMapper json, AuditLog audit, UserDirectory users) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.users = users;
    }

    record OrgSummary(UUID id, String slug, String name, OrgRole role, String color, String logoUrl) {
    }

    record OrgDetail(UUID id, String slug, String name, OrganizationSettings settings, OrgRole role, long memberCount,
            long version) {
    }

    // --- OrganizationAccess --------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public OrgContext require(CurrentUser user, String slug) {
        if (slug == null || !SLUG.matcher(slug).matches()) {
            throw ApiErrors.notFound("Organization");
        }
        return jdbc.sql("""
                SELECT o.id, o.slug, o.name, o.settings::text AS settings, m.role
                  FROM organization o JOIN membership m ON m.organization_id = o.id AND m.user_id = :user
                 WHERE o.slug = :slug AND o.deleted_at IS NULL
                """)
                .param("user", user.id()).param("slug", slug)
                .query((rs, n) -> new OrgContext(rs.getObject("id", UUID.class), rs.getString("slug"),
                        rs.getString("name"), settings(rs.getString("settings")), OrgRole.valueOf(rs.getString("role")),
                        user))
                .optional()
                .filter(ctx -> user.mayAccessOrganization(ctx.organizationId()))
                .orElseThrow(() -> ApiErrors.notFound("Organization"));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OrgRole> roleOf(UUID organizationId, UUID userId) {
        return jdbc.sql("SELECT role FROM membership WHERE organization_id = :org AND user_id = :user")
                .param("org", organizationId).param("user", userId)
                .query(String.class).optional().map(OrgRole::valueOf);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, OrgRole> rolesOf(UUID organizationId, Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("SELECT user_id, role FROM membership WHERE organization_id = :org AND user_id IN (:users)")
                .param("org", organizationId).param("users", new HashSet<>(userIds))
                .query((rs, n) -> Map.entry(rs.getObject("user_id", UUID.class), OrgRole.valueOf(rs.getString("role"))))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> teamIdsOf(UUID organizationId, UUID userId) {
        return Set.copyOf(jdbc.sql("SELECT team_id FROM team_member WHERE organization_id = :org AND user_id = :user")
                .param("org", organizationId).param("user", userId).query(UUID.class).list());
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> teamMembers(UUID organizationId, UUID teamId) {
        return Set.copyOf(jdbc.sql("SELECT user_id FROM team_member WHERE organization_id = :org AND team_id = :team")
                .param("org", organizationId).param("team", teamId).query(UUID.class).list());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean teamExists(UUID organizationId, UUID teamId) {
        return jdbc.sql("SELECT count(*) FROM team WHERE organization_id = :org AND id = :team")
                .param("org", organizationId).param("team", teamId).query(Long.class).single() > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> slugOf(UUID organizationId) {
        return jdbc.sql("SELECT slug FROM organization WHERE id = :id AND deleted_at IS NULL")
                .param("id", organizationId).query(String.class).optional();
    }

    // --- Organization lifecycle ----------------------------------------------------------

    @Transactional(readOnly = true)
    List<OrgSummary> listMine(CurrentUser user) {
        return jdbc.sql("""
                SELECT o.id, o.slug, o.name, m.role, o.settings->>'color' AS color, o.settings->>'logoUrl' AS logo_url FROM organization o
                  JOIN membership m ON m.organization_id = o.id AND m.user_id = :user
                 WHERE o.deleted_at IS NULL ORDER BY lower(o.name)
                """)
                .param("user", user.id())
                .query((rs, n) -> new OrgSummary(rs.getObject("id", UUID.class), rs.getString("slug"),
                        rs.getString("name"), OrgRole.valueOf(rs.getString("role")), rs.getString("color"), rs.getString("logo_url")))
                .list().stream().filter(o -> user.mayAccessOrganization(o.id())).toList();
    }

    @Transactional
    OrgSummary create(CurrentUser user, String name, String slug) {
        if (user.viaApiToken()) {
            throw ApiErrors.forbidden("Create organizations from a signed-in session.");
        }
        // Organizations are a server-wide resource: only a server administrator may add one.
        if (!users.isServerAdministrator(user.id())) {
            throw ApiErrors.forbidden("Only a server administrator can create organizations. Ask one to invite you instead.");
        }
        String cleanName = requireName(name);
        String cleanSlug = requireSlug(slug == null || slug.isBlank() ? slugify(cleanName) : slug);
        if (slugTaken(cleanSlug)) {
            throw ApiErrors.invalid("slug", "This URL is already taken.");
        }
        UUID id = Ids.newId();
        jdbc.sql("INSERT INTO organization (id, slug, name, settings) VALUES (:id, :slug, :name, CAST(:settings AS jsonb))")
                .param("id", id).param("slug", cleanSlug).param("name", cleanName)
                .param("settings", json.writeValueAsString(OrganizationSettings.DEFAULTS)).update();
        jdbc.sql("INSERT INTO membership (organization_id, user_id, role) VALUES (:org, :user, 'OWNER')")
                .param("org", id).param("user", user.id()).update();
        audit.record(AuditEntry.of(id, user.id(), "organization.created").target("organization", id).detail("slug", cleanSlug));
        return new OrgSummary(id, cleanSlug, cleanName, OrgRole.OWNER, null, null);
    }

    @Transactional(readOnly = true)
    OrgDetail detail(OrgContext ctx) {
        return jdbc.sql("""
                SELECT o.id, o.slug, o.name, o.settings::text AS settings, o.version,
                       (SELECT count(*) FROM membership m WHERE m.organization_id = o.id) AS members
                  FROM organization o WHERE o.id = :id
                """)
                .param("id", ctx.organizationId())
                .query((rs, n) -> new OrgDetail(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("name"),
                        settings(rs.getString("settings")), ctx.role(), rs.getLong("members"), rs.getLong("version")))
                .single();
    }

    @Transactional
    OrgDetail update(OrgContext ctx, String name, String slug, OrganizationSettings settings, long expectedVersion) {
        ctx.requireAdmin();
        String cleanName = requireName(name);
        String cleanSlug = slug == null ? ctx.slug() : requireSlug(slug);
        if (!cleanSlug.equals(ctx.slug())) {
            ctx.requireOwner();
            if (slugTaken(cleanSlug)) {
                throw ApiErrors.invalid("slug", "This URL is already taken.");
            }
        }
        OrganizationSettings clean = validate(settings == null ? ctx.settings() : settings);
        boolean colorChanged = !java.util.Objects.equals(clean.color(), ctx.settings().color());
        boolean logoChanged = !java.util.Objects.equals(clean.logoUrl(), ctx.settings().logoUrl());
        if (colorChanged || logoChanged) {
            ctx.requireOwner();
        }
        int updated = jdbc.sql("""
                UPDATE organization SET name = :name, slug = :slug, settings = CAST(:settings AS jsonb),
                       updated_at = now(), version = version + 1
                 WHERE id = :id AND version = :version AND deleted_at IS NULL
                """)
                .param("name", cleanName).param("slug", cleanSlug).param("settings", json.writeValueAsString(clean))
                .param("id", ctx.organizationId()).param("version", expectedVersion).update();
        if (updated == 0) {
            throw new org.springframework.dao.OptimisticLockingFailureException("organization changed");
        }
        audit.record(AuditEntry.of(ctx.organizationId(), ctx.actorId(), "organization.updated")
                .target("organization", ctx.organizationId()).detail("slug", cleanSlug)
                .detail("allowedEmailDomains", String.join(",", clean.allowedEmailDomains()))
                .detail("defaultProjectVisibility", clean.defaultProjectVisibility())
                .detail("membersCanCreateProjects", clean.membersCanCreateProjects()));
        OrgContext refreshed = new OrgContext(ctx.organizationId(), cleanSlug, cleanName, clean, ctx.role(), ctx.actor());
        return detail(refreshed);
    }

    /**
     * Soft-deletes the organization: it disappears for every member immediately; data is
     * retained for recovery by an operator and the slug stays reserved.
     */
    @Transactional
    void delete(OrgContext ctx, String confirmName) {
        ctx.requireOwner();
        if (confirmName == null || !confirmName.strip().equals(ctx.name())) {
            throw ApiErrors.invalid("confirmName", "Type the organization name exactly to confirm.");
        }
        jdbc.sql("UPDATE organization SET deleted_at = now(), updated_at = now(), version = version + 1 WHERE id = :id")
                .param("id", ctx.organizationId()).update();
        jdbc.sql("UPDATE invitation SET revoked_at = now() WHERE organization_id = :id AND accepted_at IS NULL AND revoked_at IS NULL")
                .param("id", ctx.organizationId()).update();
        // API tokens restricted to this organization stop working because require() excludes deleted organizations.
        audit.record(AuditEntry.of(ctx.organizationId(), ctx.actorId(), "organization.deleted")
                .target("organization", ctx.organizationId()));
    }

    // --- helpers -------------------------------------------------------------------------

    OrganizationSettings settings(String jsonText) {
        if (jsonText == null || jsonText.isBlank() || "{}".equals(jsonText.strip())) {
            return OrganizationSettings.DEFAULTS;
        }
        return json.readValue(jsonText, OrganizationSettings.class);
    }

    private static OrganizationSettings validate(OrganizationSettings settings) {
        if (!Set.of("ORGANIZATION", "PRIVATE").contains(settings.defaultProjectVisibility())) {
            throw ApiErrors.invalid("settings.defaultProjectVisibility", "Use ORGANIZATION or PRIVATE.");
        }
        List<String> domains = settings.allowedEmailDomains().stream()
                .map(d -> d.strip().toLowerCase(Locale.ROOT)).filter(d -> !d.isEmpty()).distinct().toList();
        for (String domain : domains) {
            if (!domain.matches("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$") || domain.length() > 253) {
                throw ApiErrors.invalid("settings.allowedEmailDomains", "'" + domain + "' is not a valid domain.");
            }
        }
        if (domains.size() > 50) {
            throw ApiErrors.invalid("settings.allowedEmailDomains", "At most 50 domains.");
        }

        String color = settings.color();
        if (color != null && !color.isBlank()) {
            color = color.strip();
            if (!color.matches("^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")) {
                throw ApiErrors.invalid("settings.color", "Must be a valid hex color code (e.g. #b4532a).");
            }
        } else {
            color = null;
        }

        String logoUrl = settings.logoUrl();
        if (logoUrl != null && !logoUrl.isBlank()) {
            logoUrl = logoUrl.strip();
            if (logoUrl.length() > 500_000) {
                throw ApiErrors.invalid("settings.logoUrl", "Logo must be smaller than 500 KB.");
            }
            if (!logoUrl.startsWith("http://") && !logoUrl.startsWith("https://") && !logoUrl.startsWith("data:image/")) {
                throw ApiErrors.invalid("settings.logoUrl", "Logo must be a valid image URL or image file.");
            }
        } else {
            logoUrl = null;
        }

        return new OrganizationSettings(domains, settings.defaultProjectVisibility(), settings.membersCanCreateProjects(),
                color, logoUrl);
    }

    private boolean slugTaken(String slug) {
        return RESERVED_SLUGS.contains(slug)
                || jdbc.sql("SELECT count(*) FROM organization WHERE slug = :slug").param("slug", slug).query(Long.class).single() > 0;
    }

    private static String requireName(String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty() || clean.length() > 80) {
            throw ApiErrors.invalid("name", "Use 1 to 80 characters.");
        }
        return clean;
    }

    private static String requireSlug(String slug) {
        String clean = slug.strip().toLowerCase(Locale.ROOT);
        if (!SLUG.matcher(clean).matches()) {
            throw ApiErrors.invalid("slug", "Use 1–40 lowercase letters, digits or '-', not starting or ending with '-'.");
        }
        return clean;
    }

    static String slugify(String name) {
        String slug = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > 40) {
            slug = slug.substring(0, 40).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? "org-" + Ids.newId().toString().substring(0, 8) : slug;
    }
}
