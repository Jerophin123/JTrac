package com.jtac.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jtac.DatabaseIntegrationTest;
import com.jtac.identity.UserDirectory;
import com.jtac.support.Fixtures;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Role × field matrix: workflow field rules (hidden, read-only, required) are enforced on
 * every surface: issue reads and writes, transitions, search filters and full-text,
 * aggregates, activity history, CSV export and notifications.
 *
 * <p>Setup: project RULE (Kanban) with roles Administrator (owner), Member (dev) and Viewer
 * (viewer). Rules: description and cf:budget hidden from Viewer; priority read-only and
 * estimate hidden for Member; due date required in "In Progress".
 */
@DatabaseIntegrationTest
class FieldRuleEnforcementTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private UserDirectory users;

    @Autowired
    private JsonMapper json;

    private Fixtures fx;
    private Fixtures.User owner;
    private Fixtures.User dev;
    private Fixtures.User viewer;
    private Fixtures.Org org;
    private String issueKey;
    private String inProgress;

    @BeforeEach
    void setUp() throws Exception {
        fx = new Fixtures(mvc, jdbc, users);
        owner = fx.user("Owner");
        dev = fx.user("Dev");
        viewer = fx.user("Viewer");
        org = fx.org(owner);
        fx.member(org, dev, "MEMBER");
        fx.member(org, viewer, "GUEST");
        fx.project(owner, org, "RULE", "KANBAN");

        String catalog = owner.client().get(org.path("/projects/RULE/catalog")).andReturn().getResponse().getContentAsString();
        String memberRole = roleId(catalog, "MEMBER");
        String viewerRole = roleId(catalog, "VIEWER");
        owner.client().put(org.path("/projects/RULE/members/" + dev.id()), "{\"roleId\":\"" + memberRole + "\"}")
                .andExpect(status().isNoContent());
        owner.client().put(org.path("/projects/RULE/members/" + viewer.id()), "{\"roleId\":\"" + viewerRole + "\"}")
                .andExpect(status().isNoContent());
        owner.client().post(org.path("/projects/RULE/fields"), "{\"key\":\"budget\",\"name\":\"Budget\",\"type\":\"NUMBER\"}")
                .andExpect(status().isCreated());

        String overview = owner.client().get(org.path("/projects/RULE/workflow")).andReturn().getResponse().getContentAsString();
        inProgress = JsonPath.<List<String>>read(overview, "$.statuses[?(@.name=='In Progress')].id").getFirst();
        Map<String, Object> definition = new LinkedHashMap<>(JsonPath.read(overview, "$.published.definition"));
        List<Map<String, Object>> rules = new ArrayList<>();
        rules.add(rule(null, viewerRole, "description", "HIDDEN"));
        rules.add(rule(null, viewerRole, "cf:budget", "HIDDEN"));
        rules.add(rule(null, memberRole, "priority", "READ_ONLY"));
        rules.add(rule(null, memberRole, "estimate", "HIDDEN"));
        rules.add(rule(inProgress, null, "dueDate", "REQUIRED"));
        definition.put("rules", rules);
        owner.client().put(org.path("/projects/RULE/workflow/draft"), json.writeValueAsString(definition))
                .andExpect(status().isOk());
        owner.client().post(org.path("/projects/RULE/workflow/draft/publish"), "{}").andExpect(status().isOk());

        issueKey = fx.issue(owner, org, "RULE", "{\"title\":\"Rocket\",\"description\":\"launch codes 1234\","
                + "\"priority\":\"HIGH\",\"estimate\":8,\"customValues\":{\"budget\":5000}}");
        owner.client().put(org.path("/issues/" + issueKey + "/watch"), null);
        viewer.client().put(org.path("/issues/" + issueKey + "/watch"), null).andExpect(status().isNoContent());
        dev.client().put(org.path("/issues/" + issueKey + "/watch"), null).andExpect(status().isNoContent());
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    @Test
    void hiddenFieldsAreAbsentFromIssueResponses() throws Exception {
        viewer.client().get(org.path("/issues/" + issueKey)).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Rocket"))
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.customValues.budget").doesNotExist())
                .andExpect(jsonPath("$.fieldAccess.description").value("HIDDEN"))
                .andExpect(jsonPath("$.estimate").value(8));
        dev.client().get(org.path("/issues/" + issueKey)).andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("launch codes 1234"))
                .andExpect(jsonPath("$.customValues.budget").value(5000))
                .andExpect(jsonPath("$.estimate").doesNotExist())
                .andExpect(jsonPath("$.fieldAccess.priority").value("READ_ONLY"));
        owner.client().get(org.path("/issues/" + issueKey)).andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("launch codes 1234"))
                .andExpect(jsonPath("$.estimate").value(8));

        viewer.client().post(org.path("/issues/search"), "{\"filter\":{\"projectKeys\":[\"RULE\"]}}")
                .andExpect(jsonPath("$.items[0].customValues.budget").doesNotExist())
                .andExpect(jsonPath("$.items[0].hiddenFields").value(hasItem("cf:budget")));
    }

    @Test
    void searchNeverMatchesOnHiddenValues() throws Exception {
        String byDescription = "{\"filter\":{\"projectKeys\":[\"RULE\"],\"text\":\"launch\"}}";
        viewer.client().post(org.path("/issues/search"), byDescription).andExpect(jsonPath("$.total").value(0));
        dev.client().post(org.path("/issues/search"), byDescription).andExpect(jsonPath("$.total").value(1));
        viewer.client().post(org.path("/issues/search"), "{\"filter\":{\"projectKeys\":[\"RULE\"],\"text\":\"Rocket\"}}")
                .andExpect(jsonPath("$.total").value(1));

        String byBudget = "{\"filter\":{\"projectKeys\":[\"RULE\"],\"custom\":[{\"field\":\"budget\",\"op\":\"gt\",\"value\":1000}]}}";
        viewer.client().post(org.path("/issues/search"), byBudget).andExpect(jsonPath("$.total").value(0));
        dev.client().post(org.path("/issues/search"), byBudget).andExpect(jsonPath("$.total").value(1));

        // Aggregated estimates only include values the caller can see.
        String aggregate = "{\"filter\":{\"projectKeys\":[\"RULE\"]},\"groupBy\":\"status\"}";
        dev.client().post(org.path("/issues/aggregate"), aggregate).andExpect(jsonPath("$[0].estimate").value(0.0));
        owner.client().post(org.path("/issues/aggregate"), aggregate).andExpect(jsonPath("$[0].estimate").value(8.0));
    }

    @Test
    void readOnlyFieldsCannotBeChangedAndRequiredFieldsMustBeFilled() throws Exception {
        long version = version(dev);
        // Read-only and hidden fields are rejected as field-level validation errors.
        dev.client().patch(org.path("/issues/" + issueKey), "{\"changes\":{\"priority\":\"LOW\"},\"version\":" + version + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("priority")));
        dev.client().patch(org.path("/issues/" + issueKey), "{\"changes\":{\"title\":\"Rocket 2\"},\"version\":" + version + "}")
                .andExpect(status().isOk());
        version = version(dev);
        // Viewers cannot edit at all (no EDIT_ISSUES); hidden fields cannot be set even by editors.
        viewer.client().patch(org.path("/issues/" + issueKey), "{\"changes\":{\"title\":\"x\"},\"version\":" + version + "}")
                .andExpect(status().isForbidden());
        dev.client().patch(org.path("/issues/" + issueKey), "{\"changes\":{\"estimate\":3},\"version\":" + version + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("estimate")));

        // Entering "In Progress" requires a due date.
        dev.client().patch(org.path("/issues/" + issueKey), "{\"changes\":{\"statusId\":\"" + inProgress + "\"},\"version\":" + version + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("dueDate")));
        dev.client().patch(org.path("/issues/" + issueKey), "{\"changes\":{\"statusId\":\"" + inProgress
                        + "\",\"dueDate\":\"2026-12-01\"},\"version\":" + version + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status.name").value("In Progress"));
    }

    @Test
    void activityExportAndNotificationsRespectHiddenFields() throws Exception {
        long version = version(owner);
        owner.client().patch(org.path("/issues/" + issueKey), "{\"changes\":{\"description\":\"new secret\"},\"version\":" + version + "}")
                .andExpect(status().isOk());

        viewer.client().get(org.path("/issues/" + issueKey + "/activity")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].field").value(not(hasItem("description"))));
        dev.client().get(org.path("/issues/" + issueKey + "/activity")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].field").value(hasItem("description")));

        String csv = viewer.client().post(org.path("/issues/export"), "{\"filter\":{\"projectKeys\":[\"RULE\"]}}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(csv).contains("Rocket").doesNotContain("secret").doesNotContain("5000");

        // The dev (who can see the description) is told about the change; the viewer (who
        // cannot) gets no notification about an invisible change.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                dev.client().get(org.path("/notifications")).andExpect(jsonPath("$.items[0].body").value(
                        containsString("description"))));
        String viewerInbox = viewer.client().get(org.path("/notifications")).andReturn().getResponse().getContentAsString();
        assertThat(viewerInbox).doesNotContain("description");
    }

    private long version(Fixtures.User user) throws Exception {
        return ((Number) JsonPath.read(user.client().get(org.path("/issues/" + issueKey)).andReturn().getResponse()
                .getContentAsString(), "$.version")).longValue();
    }

    private static String roleId(String catalog, String systemKey) {
        return JsonPath.<List<String>>read(catalog, "$.roles[?(@.systemKey=='" + systemKey + "')].id").getFirst();
    }

    private static Map<String, Object> rule(String statusId, String roleId, String field, String access) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("statusId", statusId);
        rule.put("roleId", roleId);
        rule.put("field", field);
        rule.put("access", access);
        return rule;
    }
}
