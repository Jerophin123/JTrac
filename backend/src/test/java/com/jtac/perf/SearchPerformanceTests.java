package com.jtac.perf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jtac.DatabaseIntegrationTest;
import com.jtac.identity.UserDirectory;
import com.jtac.support.Fixtures;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Response times of the read paths that scale with data volume, measured through the full
 * HTTP stack against a project seeded with {@value #ISSUES} issues (plus a second project the
 * caller cannot see, so permission filtering is part of every query). Budgets are p95 over
 * repeated calls and are deliberately generous for a developer laptop; the measured values
 * are logged for the performance report.
 */
@DatabaseIntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchPerformanceTests {

    private static final Logger log = LoggerFactory.getLogger(SearchPerformanceTests.class);
    static final int ISSUES = 20_000;
    private static final long BUDGET_MS = 750;
    private static final int RUNS = 15;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private UserDirectory users;

    private Fixtures fx;
    private Fixtures.User owner;
    private Fixtures.Org org;
    private final Map<String, Long> results = new LinkedHashMap<>();

    @BeforeAll
    void seed() throws Exception {
        fx = new Fixtures(mvc, jdbc, users);
        owner = fx.user("Perf Owner");
        org = fx.org(owner);
        fx.project(owner, org, "PERF", "KANBAN");
        fx.project(owner, org, "HIDE", "KANBAN", "PRIVATE");
        Fixtures.User other = fx.user("Perf Other");
        fx.member(org, other, "MEMBER");
        seed("PERF", ISSUES, other.id());
        seed("HIDE", ISSUES / 4, other.id());
        jdbc.sql("ANALYZE issue").update();
    }

    /** Bulk-inserts realistic issues: mixed statuses, priorities, assignees, dates and text. */
    private void seed(String key, int count, UUID assignee) {
        UUID project = jdbc.sql("SELECT id FROM project WHERE organization_id = :org AND key = :key")
                .param("org", org.id()).param("key", key).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO issue (id, organization_id, project_id, number, type_id, title, description, status_id, priority,
                                   assignee_id, reporter_id, due_date, estimate, rank, created_at, updated_at, resolved_at)
                SELECT uuidv7(), :org, :project, g,
                       (SELECT id FROM issue_type WHERE project_id = :project AND NOT is_subtask ORDER BY position LIMIT 1),
                       'Issue ' || g || ' ' || (ARRAY['login','payment','search','report','export','billing','upload'])[1 + g % 7]
                           || ' ' || (ARRAY['fails','is slow','needs polish','crashes','times out'])[1 + g % 5],
                       'Steps to reproduce number ' || g || ' with gateway ' || (g % 97),
                       s.ids[1 + g % cardinality(s.ids)],
                       (ARRAY['URGENT','HIGH','MEDIUM','LOW','NONE'])[1 + g % 5],
                       CASE WHEN g % 3 = 0 THEN :assignee END, :reporter,
                       CASE WHEN g % 4 = 0 THEN DATE '2026-01-01' + (g % 365) END,
                       (g % 13), g * 1024,
                       now() - make_interval(days => g % 400), now() - make_interval(hours => g % 5000),
                       CASE WHEN g % 6 = 0 THEN now() - make_interval(days => g % 300) END
                  FROM generate_series(1, :count) g,
                       (SELECT array_agg(id ORDER BY position) AS ids FROM status WHERE project_id = :project) s
                """)
                .param("org", org.id()).param("project", project).param("assignee", assignee)
                .param("reporter", owner.id()).param("count", count).update();
        jdbc.sql("UPDATE project SET next_issue_number = :next WHERE id = :project")
                .param("next", count + 1).param("project", project).update();
    }

    @AfterAll
    void cleanUp() {
        results.forEach((name, p95) -> log.info("perf {} p95={}ms (budget {}ms, {} issues)", name, p95, BUDGET_MS, ISSUES));
        fx.close();
    }

    @Test
    void searchFilterSortAndAggregateStayWithinBudget() throws Exception {
        measure("default list (rank, page 1)", () -> owner.client().post(org.path("/issues/search"),
                "{\"filter\":{\"projectKeys\":[\"PERF\"]},\"size\":50}"));
        measure("deep page (page 150)", () -> owner.client().post(org.path("/issues/search"),
                "{\"filter\":{\"projectKeys\":[\"PERF\"]},\"page\":150,\"size\":50}"));
        measure("full-text", () -> owner.client().post(org.path("/issues/search"),
                "{\"filter\":{\"text\":\"payment timeout\"},\"size\":50}"));
        measure("filters + sort by priority", () -> owner.client().post(org.path("/issues/search"),
                "{\"filter\":{\"priorities\":[\"URGENT\",\"HIGH\"],\"resolved\":false,\"categories\":[\"TODO\",\"IN_PROGRESS\"]},"
                        + "\"sort\":\"priority\",\"size\":50}"));
        measure("sort by due date desc", () -> owner.client().post(org.path("/issues/search"),
                "{\"filter\":{\"projectKeys\":[\"PERF\"],\"sort\":\"dueDate\",\"direction\":\"desc\"},\"size\":50}"));
        measure("aggregate by status", () -> owner.client().post(org.path("/issues/aggregate"),
                "{\"filter\":{\"projectKeys\":[\"PERF\"]},\"groupBy\":\"status\"}"));
        measure("aggregate by assignee", () -> owner.client().post(org.path("/issues/aggregate"),
                "{\"filter\":{},\"groupBy\":\"assignee\"}"));
        measure("board (6 columns x 50)", () -> owner.client().post(org.path("/projects/PERF/board?perColumn=50"), "{}"));
        measure("global search", () -> owner.client().get(org.path("/search?q=billing")));
        measure("issue by key", () -> owner.client().get(org.path("/issues/PERF-12345")));

        results.forEach((name, p95) -> assertThat(p95).as(name + " p95 (ms)").isLessThan(BUDGET_MS));
    }

    private interface Call {
        org.springframework.test.web.servlet.ResultActions run() throws Exception;
    }

    private void measure(String name, Call call) throws Exception {
        call.run().andExpect(status().isOk()); // warm-up
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < RUNS; i++) {
            long start = System.nanoTime();
            call.run().andExpect(status().isOk());
            times.add((System.nanoTime() - start) / 1_000_000);
        }
        times.sort(Long::compare);
        results.put(name, times.get((int) Math.ceil(RUNS * 0.95) - 1));
    }
}
