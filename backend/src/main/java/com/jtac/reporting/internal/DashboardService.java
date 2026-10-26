package com.jtac.reporting.internal;

import com.jtac.issue.IssueFilter;
import com.jtac.issue.IssueSearch;
import com.jtac.issue.IssueSummary;
import com.jtac.organization.OrgContext;
import com.jtac.planning.MilestoneProgress;
import com.jtac.project.ProjectAccess;
import com.jtac.shared.Ids;
import com.jtac.shared.web.ApiErrors;
import com.jtac.shared.web.PageResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dashboards: a named, ordered set of widgets, private to the owner or shared with the
 * organization. Widget configuration is validated on save; widget data is computed per
 * viewer on request.
 */
@Service
class DashboardService {

    static final Set<String> KINDS = Set.of("BREAKDOWN", "COUNT", "CREATED_VS_RESOLVED", "MILESTONE_PROGRESS", "ISSUE_LIST");
    private static final int MAX_WIDGETS = 24;
    private static final int MAX_DASHBOARDS_PER_OWNER = 50;

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final IssueSearch issues;
    private final ProjectAccess projects;
    private final MilestoneProgress milestoneProgress;
    private final Clock clock;

    DashboardService(JdbcClient jdbc, JsonMapper json, IssueSearch issues, ProjectAccess projects,
            MilestoneProgress milestoneProgress, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.issues = issues;
        this.projects = projects;
        this.milestoneProgress = milestoneProgress;
        this.clock = clock;
    }

    /**
     * Widget configuration. {@code filter} narrows the issues; {@code groupBy} is used by
     * BREAKDOWN; {@code days} by CREATED_VS_RESOLVED; {@code projectKey} by MILESTONE_PROGRESS;
     * {@code limit} by ISSUE_LIST.
     */
    record WidgetConfig(IssueFilter filter, String groupBy, Integer days, String projectKey, Integer limit) {
        static WidgetConfig empty() {
            return new WidgetConfig(null, null, null, null, null);
        }

        IssueFilter filterOrEmpty() {
            return filter == null ? IssueFilter.empty() : filter;
        }
    }

    record Widget(UUID id, String kind, String title, WidgetConfig config, int position, int width) {
    }

    record DashboardSummary(UUID id, String name, boolean shared, UUID ownerId, String ownerName, boolean editable,
            Instant updatedAt, long version) {
    }

    record Dashboard(UUID id, String name, boolean shared, UUID ownerId, String ownerName, boolean editable,
            Instant updatedAt, long version, List<Widget> widgets) {
    }

    record WidgetInput(String kind, String title, WidgetConfig config, Integer width) {
    }

    record DashboardInput(String name, Boolean shared, List<WidgetInput> widgets) {
    }

    /** Data for one widget; only the property matching {@code kind} is set. */
    record WidgetData(String kind, List<IssueSearch.Bucket> buckets, Long count, List<IssueSearch.DayCount> days,
            List<MilestoneProgress.Progress> milestones, List<IssueSummary> issues, Long total) {
    }

    // --- dashboards ----------------------------------------------------------------------

    @Transactional(readOnly = true)
    List<DashboardSummary> list(OrgContext ctx) {
        return jdbc.sql("""
                SELECT d.id, d.name, d.shared, d.owner_id, u.display_name, d.updated_at, d.version
                  FROM dashboard d JOIN user_account u ON u.id = d.owner_id
                 WHERE d.organization_id = :org AND (d.owner_id = :user OR d.shared)
                 ORDER BY lower(d.name), d.id
                """)
                .param("org", ctx.organizationId()).param("user", ctx.actorId())
                .query((rs, n) -> {
                    UUID owner = rs.getObject("owner_id", UUID.class);
                    boolean shared = rs.getBoolean("shared");
                    return new DashboardSummary(rs.getObject("id", UUID.class), rs.getString("name"), shared, owner,
                            rs.getString("display_name"), editable(ctx, owner, shared),
                            rs.getObject("updated_at", OffsetDateTime.class).toInstant(), rs.getLong("version"));
                })
                .list();
    }

    @Transactional(readOnly = true)
    Dashboard get(OrgContext ctx, UUID id) {
        DashboardSummary d = load(ctx, id);
        List<Widget> widgets = jdbc.sql("""
                SELECT id, kind, title, config::text AS config, position, width FROM dashboard_widget
                 WHERE dashboard_id = :id AND organization_id = :org ORDER BY position, id
                """)
                .param("id", id).param("org", ctx.organizationId())
                .query(this::widget).list();
        return new Dashboard(d.id(), d.name(), d.shared(), d.ownerId(), d.ownerName(), d.editable(), d.updatedAt(),
                d.version(), widgets);
    }

    @Transactional
    Dashboard create(OrgContext ctx, DashboardInput input) {
        String name = name(input.name());
        long owned = jdbc.sql("SELECT count(*) FROM dashboard WHERE organization_id = :org AND owner_id = :user")
                .param("org", ctx.organizationId()).param("user", ctx.actorId()).query(Long.class).single();
        if (owned >= MAX_DASHBOARDS_PER_OWNER) {
            throw ApiErrors.conflict("You have reached the limit of " + MAX_DASHBOARDS_PER_OWNER + " dashboards.");
        }
        List<WidgetInput> widgets = validateWidgets(ctx, input.widgets());
        UUID id = Ids.newId();
        jdbc.sql("INSERT INTO dashboard (id, organization_id, owner_id, name, shared) VALUES (:id, :org, :owner, :name, :shared)")
                .param("id", id).param("org", ctx.organizationId()).param("owner", ctx.actorId()).param("name", name)
                .param("shared", Boolean.TRUE.equals(input.shared())).update();
        insertWidgets(ctx, id, widgets);
        return get(ctx, id);
    }

    /** Replaces name, sharing and the full widget list (the editor saves the whole layout). */
    @Transactional
    Dashboard update(OrgContext ctx, UUID id, long version, DashboardInput input) {
        DashboardSummary existing = load(ctx, id);
        requireEditable(existing);
        String name = name(input.name());
        boolean shared = input.shared() == null ? existing.shared() : input.shared();
        if (shared != existing.shared() && !existing.ownerId().equals(ctx.actorId())) {
            throw ApiErrors.forbidden("Only the owner can change who sees this dashboard.");
        }
        List<WidgetInput> widgets = validateWidgets(ctx, input.widgets());
        int updated = jdbc.sql("""
                UPDATE dashboard SET name = :name, shared = :shared, updated_at = now(), version = version + 1
                 WHERE id = :id AND organization_id = :org AND version = :version
                """)
                .param("name", name).param("shared", shared).param("id", id).param("org", ctx.organizationId())
                .param("version", version).update();
        if (updated == 0) {
            throw new OptimisticLockingFailureException("Dashboard was changed by someone else.");
        }
        jdbc.sql("DELETE FROM dashboard_widget WHERE dashboard_id = :id AND organization_id = :org")
                .param("id", id).param("org", ctx.organizationId()).update();
        insertWidgets(ctx, id, widgets);
        return get(ctx, id);
    }

    @Transactional
    void delete(OrgContext ctx, UUID id) {
        requireEditable(load(ctx, id));
        jdbc.sql("DELETE FROM dashboard WHERE id = :id AND organization_id = :org")
                .param("id", id).param("org", ctx.organizationId()).update();
    }

    // --- widget data -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    WidgetData data(OrgContext ctx, UUID dashboardId, UUID widgetId) {
        load(ctx, dashboardId);
        Widget widget = jdbc.sql("""
                SELECT id, kind, title, config::text AS config, position, width FROM dashboard_widget
                 WHERE id = :id AND dashboard_id = :dashboard AND organization_id = :org
                """)
                .param("id", widgetId).param("dashboard", dashboardId).param("org", ctx.organizationId())
                .query(this::widget).optional().orElseThrow(() -> ApiErrors.notFound("Widget"));
        return compute(ctx, widget.kind(), widget.config());
    }

    /** Computes widget data for an unsaved configuration (editor preview). */
    @Transactional(readOnly = true)
    WidgetData preview(OrgContext ctx, WidgetInput input) {
        WidgetInput valid = validateWidget(ctx, input, 0);
        return compute(ctx, valid.kind(), valid.config());
    }

    private WidgetData compute(OrgContext ctx, String kind, WidgetConfig config) {
        IssueFilter filter = config.filterOrEmpty();
        return switch (kind) {
            case "BREAKDOWN" -> new WidgetData(kind, issues.countBy(ctx, filter, config.groupBy()), null, null, null, null, null);
            case "COUNT" -> new WidgetData(kind, null, issues.search(ctx, filter, 0, 1).total(), null, null, null, null);
            case "CREATED_VS_RESOLVED" -> {
                LocalDate to = LocalDate.now(clock.withZone(ZoneOffset.UTC));
                LocalDate from = to.minusDays(config.days() - 1L);
                yield new WidgetData(kind, null, null, issues.createdVsResolved(ctx, filter, from, to), null, null, null);
            }
            case "MILESTONE_PROGRESS" -> new WidgetData(kind, null, null, null, milestoneProgress.forProject(ctx, config.projectKey(), filter),
                    null, null);
            case "ISSUE_LIST" -> {
                PageResponse<IssueSummary> page = issues.search(ctx, filter, 0, config.limit());
                yield new WidgetData(kind, null, null, null, null, page.items(), page.total());
            }
            default -> throw ApiErrors.invalid("kind", "Unknown widget kind.");
        };
    }

    // --- validation and helpers --------------------------------------------------------------

    private List<WidgetInput> validateWidgets(OrgContext ctx, List<WidgetInput> widgets) {
        if (widgets == null) {
            return List.of();
        }
        if (widgets.size() > MAX_WIDGETS) {
            throw ApiErrors.invalid("widgets", "A dashboard can have at most " + MAX_WIDGETS + " widgets.");
        }
        List<WidgetInput> result = new ArrayList<>();
        for (int i = 0; i < widgets.size(); i++) {
            result.add(validateWidget(ctx, widgets.get(i), i));
        }
        return result;
    }

    private WidgetInput validateWidget(OrgContext ctx, WidgetInput w, int index) {
        String prefix = "widgets[" + index + "].";
        if (w == null || w.kind() == null || !KINDS.contains(w.kind())) {
            throw ApiErrors.invalid(prefix + "kind", "Choose one of " + KINDS + ".");
        }
        String title = w.title() == null ? "" : w.title().strip();
        if (title.isEmpty() || title.length() > 80) {
            throw ApiErrors.invalid(prefix + "title", "Enter a title of 1 to 80 characters.");
        }
        int width = w.width() == null ? 1 : w.width();
        if (width != 1 && width != 2) {
            throw ApiErrors.invalid(prefix + "width", "Width is 1 or 2 columns.");
        }
        WidgetConfig c = w.config() == null ? WidgetConfig.empty() : w.config();
        if (c.filter() != null && c.filter().sort() != null && !IssueFilter.SORT_FIELDS.contains(c.filter().sort())) {
            throw ApiErrors.invalid(prefix + "config.filter.sort", "Unknown sort field.");
        }
        String groupBy = null;
        Integer days = null;
        String projectKey = null;
        Integer limit = null;
        switch (w.kind()) {
            case "BREAKDOWN" -> {
                groupBy = c.groupBy() == null ? "status" : c.groupBy();
                if (!IssueSearch.GROUPS.contains(groupBy)) {
                    throw ApiErrors.invalid(prefix + "config.groupBy", "Use one of " + IssueSearch.GROUPS + ".");
                }
            }
            case "CREATED_VS_RESOLVED" -> {
                days = c.days() == null ? 30 : c.days();
                if (days < 7 || days > 365) {
                    throw ApiErrors.invalid(prefix + "config.days", "Use 7 to 365 days.");
                }
            }
            case "MILESTONE_PROGRESS" -> {
                if (c.projectKey() == null || c.projectKey().isBlank()) {
                    throw ApiErrors.invalid(prefix + "config.projectKey", "Choose a project.");
                }
                projectKey = projects.require(ctx, c.projectKey()).project().key();
            }
            case "ISSUE_LIST" -> {
                limit = c.limit() == null ? 10 : c.limit();
                if (limit < 1 || limit > 25) {
                    throw ApiErrors.invalid(prefix + "config.limit", "Show 1 to 25 issues.");
                }
            }
            default -> {
            }
        }
        return new WidgetInput(w.kind(), title, new WidgetConfig(c.filter(), groupBy, days, projectKey, limit), width);
    }

    private void insertWidgets(OrgContext ctx, UUID dashboardId, List<WidgetInput> widgets) {
        for (int i = 0; i < widgets.size(); i++) {
            WidgetInput w = widgets.get(i);
            jdbc.sql("""
                    INSERT INTO dashboard_widget (id, organization_id, dashboard_id, kind, title, config, position, width)
                    VALUES (:id, :org, :dashboard, :kind, :title, CAST(:config AS jsonb), :position, :width)
                    """)
                    .param("id", Ids.newId()).param("org", ctx.organizationId()).param("dashboard", dashboardId)
                    .param("kind", w.kind()).param("title", w.title()).param("config", json.writeValueAsString(w.config()))
                    .param("position", i).param("width", w.width()).update();
        }
    }

    private static String name(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty() || name.length() > 80) {
            throw ApiErrors.invalid("name", "Enter a name of 1 to 80 characters.");
        }
        return name;
    }

    private static boolean editable(OrgContext ctx, UUID owner, boolean shared) {
        return owner.equals(ctx.actorId()) || (shared && ctx.isAdmin());
    }

    private static void requireEditable(DashboardSummary d) {
        if (!d.editable()) {
            throw ApiErrors.forbidden("Only the owner of this dashboard (or an organization admin, for shared dashboards) can change it.");
        }
    }

    private DashboardSummary load(OrgContext ctx, UUID id) {
        return list(ctx).stream().filter(d -> d.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiErrors.notFound("Dashboard"));
    }

    private Widget widget(ResultSet rs, int n) throws SQLException {
        WidgetConfig config;
        try {
            config = json.readValue(rs.getString("config"), WidgetConfig.class);
        } catch (JacksonException e) {
            config = WidgetConfig.empty();
        }
        return new Widget(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("title"), config,
                rs.getInt("position"), rs.getInt("width"));
    }
}
