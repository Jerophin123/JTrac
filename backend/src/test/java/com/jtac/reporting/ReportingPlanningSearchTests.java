package com.jtac.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jtac.DatabaseIntegrationTest;
import com.jtac.identity.UserDirectory;
import com.jtac.support.Fixtures;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** Saved views, dashboards with verified calculations, the timeline and global search. */
@DatabaseIntegrationTest
class ReportingPlanningSearchTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private UserDirectory users;

    private Fixtures fx;
    private Fixtures.User owner;
    private Fixtures.User member;
    private Fixtures.Org org;
    private Map<String, String> statuses;
    private String milestoneId;

    @BeforeEach
    void setUp() throws Exception {
        fx = new Fixtures(mvc, jdbc, users);
        owner = fx.user("Owner");
        member = fx.user("Member");
        org = fx.org(owner);
        fx.member(org, member, "MEMBER");
        fx.project(owner, org, "REP", "KANBAN");
        String overview = owner.client().get(org.path("/projects/REP/workflow")).andReturn().getResponse().getContentAsString();
        statuses = JsonPath.<List<Map<String, Object>>>read(overview, "$.statuses").stream()
                .collect(Collectors.toMap(s -> (String) s.get("name"), s -> (String) s.get("id")));
        milestoneId = JsonPath.read(owner.client().post(org.path("/projects/REP/milestones"),
                "{\"name\":\"v1.0\",\"dueDate\":\"2026-12-31\"}").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    private String issue(String title, String extra) throws Exception {
        return fx.issue(owner, org, "REP", "{\"title\":\"" + title + "\",\"milestoneId\":\"" + milestoneId + "\"" + extra + "}");
    }

    private void moveTo(String key, String status) throws Exception {
        long version = ((Number) JsonPath.read(owner.client().get(org.path("/issues/" + key)).andReturn().getResponse()
                .getContentAsString(), "$.version")).longValue();
        owner.client().patch(org.path("/issues/" + key), "{\"changes\":{\"statusId\":\"" + statuses.get(status)
                + "\"},\"version\":" + version + "}").andExpect(status().isOk());
    }

    @Test
    void milestoneProgressExcludesCanceledIssuesAndDashboardsComputePerViewer() throws Exception {
        String a = issue("Alpha", ",\"startDate\":\"2026-11-01\",\"dueDate\":\"2026-11-10\"");
        String b = issue("Beta", ",\"dueDate\":\"2026-11-20\"");
        issue("Gamma", "");
        issue("Delta", "");
        moveTo(a, "Done");
        moveTo(b, "Canceled");
        // 4 issues, 1 done, 1 canceled: progress = 1 / (4 - 1).
        owner.client().get(org.path("/projects/REP/milestones/progress"))
                .andExpect(jsonPath("$[0].total").value(4)).andExpect(jsonPath("$[0].done").value(1))
                .andExpect(jsonPath("$[0].canceled").value(1))
                .andExpect(jsonPath("$[0].progress").value(closeTo(1.0 / 3, 1e-9)));

        String dashboard = owner.client().post(org.path("/dashboards"), """
                {"name":"Delivery","shared":true,"widgets":[
                  {"kind":"BREAKDOWN","title":"By status","config":{"groupBy":"status","filter":{"projectKeys":["REP"]}}},
                  {"kind":"COUNT","title":"Open","config":{"filter":{"projectKeys":["REP"],"resolved":false}}},
                  {"kind":"CREATED_VS_RESOLVED","title":"Flow","config":{"days":14,"filter":{"projectKeys":["REP"]}}},
                  {"kind":"MILESTONE_PROGRESS","title":"Milestones","config":{"projectKey":"REP"}},
                  {"kind":"ISSUE_LIST","title":"Due soon","width":2,"config":{"limit":5,"filter":{"projectKeys":["REP"],"sort":"dueDate","direction":"asc"}}}
                ]}""").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(dashboard, "$.id");
        List<String> widgets = JsonPath.read(dashboard, "$.widgets[*].id");
        assertThat(widgets).hasSize(5);

        String breakdown = data(owner, id, widgets.get(0));
        Map<String, Integer> byStatus = JsonPath.<List<Map<String, Object>>>read(breakdown, "$.buckets").stream()
                .collect(Collectors.toMap(m -> (String) m.get("label"), m -> ((Number) m.get("count")).intValue()));
        assertThat(byStatus).containsEntry("Todo", 2).containsEntry("Done", 1).containsEntry("Canceled", 1);
        assertThat(JsonPath.<Integer>read(data(owner, id, widgets.get(1)), "$.count")).isEqualTo(2);
        String flow = data(owner, id, widgets.get(2));
        assertThat(JsonPath.<List<Object>>read(flow, "$.days")).hasSize(14);
        assertThat(JsonPath.<List<Integer>>read(flow, "$.days[*].created").stream().mapToInt(Integer::intValue).sum()).isEqualTo(4);
        assertThat(JsonPath.<List<Integer>>read(flow, "$.days[*].resolved").stream().mapToInt(Integer::intValue).sum()).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(data(owner, id, widgets.get(3)), "$.milestones[0].done")).isEqualTo(1);
        assertThat(JsonPath.<String>read(data(owner, id, widgets.get(4)), "$.issues[0].key")).isEqualTo(a);

        // A member sees the shared dashboard but cannot edit it; data is computed for them.
        member.client().get(org.path("/dashboards/" + id)).andExpect(jsonPath("$.editable").value(false));
        member.client().put(org.path("/dashboards/" + id), "{\"version\":0,\"dashboard\":{\"name\":\"x\"}}")
                .andExpect(status().isForbidden());
        // Make the project private: the member's widget now counts nothing.
        long version = ((Number) JsonPath.read(owner.client().get(org.path("/projects/REP")).andReturn().getResponse()
                .getContentAsString(), "$.version")).longValue();
        owner.client().patch(org.path("/projects/REP"), "{\"name\":\"Project REP\",\"visibility\":\"PRIVATE\",\"version\":"
                + version + "}").andExpect(status().isOk());
        assertThat(JsonPath.<Integer>read(data(member, id, widgets.get(1)), "$.count")).isEqualTo(0);
        assertThat(JsonPath.<Integer>read(data(owner, id, widgets.get(1)), "$.count")).isEqualTo(2);

        owner.client().post(org.path("/widgets/preview"), "{\"kind\":\"BREAKDOWN\",\"title\":\"t\",\"config\":{\"groupBy\":\"bogus\"}}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void timelineShowsScheduledIssuesAndDependencies() throws Exception {
        String a = issue("Design", ",\"startDate\":\"2026-11-01\",\"dueDate\":\"2026-11-05\"");
        String b = issue("Build", ",\"startDate\":\"2026-11-06\",\"dueDate\":\"2026-11-20\"");
        issue("Someday", "");
        owner.client().post(org.path("/issues/" + a + "/links"), "{\"targetKey\":\"" + b + "\",\"type\":\"BLOCKS\"}")
                .andExpect(status().isCreated());
        owner.client().post(org.path("/projects/REP/timeline"), "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduled.length()").value(2))
                .andExpect(jsonPath("$.scheduled[0].key").value(a))
                .andExpect(jsonPath("$.unscheduled").value(1))
                .andExpect(jsonPath("$.dependencies[0].blockerKey").value(a))
                .andExpect(jsonPath("$.dependencies[0].blockedKey").value(b))
                .andExpect(jsonPath("$.milestones[0].name").value("v1.0"));
    }

    @Test
    void savedViewsAreOwnedAndOptionallyShared() throws Exception {
        String privateView = JsonPath.read(member.client().post(org.path("/views"),
                "{\"name\":\"My work\",\"query\":{\"assignees\":[\"me\"],\"sort\":\"updated\"},\"display\":{\"columns\":[\"key\",\"title\"]}}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        String sharedView = JsonPath.read(member.client().post(org.path("/views"),
                "{\"name\":\"Team board\",\"kind\":\"BOARD\",\"projectKey\":\"REP\",\"shared\":true}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        member.client().post(org.path("/views"), "{\"name\":\"Bad\",\"kind\":\"BOARD\"}").andExpect(status().isBadRequest());
        member.client().post(org.path("/views"), "{\"name\":\"Bad\",\"query\":{\"sort\":\"password\"}}").andExpect(status().isBadRequest());

        owner.client().get(org.path("/views/" + privateView)).andExpect(status().isNotFound());
        owner.client().get(org.path("/views")).andExpect(jsonPath("$[*].name").value(contains("Team board")));
        // Admins may curate shared views; the owner keeps control of private ones.
        owner.client().put(org.path("/views/" + sharedView), "{\"version\":0,\"view\":{\"name\":\"Team board (curated)\",\"kind\":\"BOARD\",\"projectKey\":\"REP\"}}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.shared").value(true));
        member.client().put(org.path("/views/" + sharedView), "{\"version\":0,\"view\":{\"name\":\"stale\",\"kind\":\"BOARD\",\"projectKey\":\"REP\"}}")
                .andExpect(status().isConflict());
        member.client().get(org.path("/views/" + privateView)).andExpect(jsonPath("$.query.assignees[0]").value("me"))
                .andExpect(jsonPath("$.display.columns[1]").value("title"));
    }

    @Test
    void globalSearchFindsIssuesProjectsAndPeople() throws Exception {
        String key = issue("Payment gateway timeout", "");
        owner.client().get(org.path("/search?q=" + key.toLowerCase())).andExpect(jsonPath("$.issues[0].key").value(key));
        owner.client().get(org.path("/search?q=gateway")).andExpect(jsonPath("$.issues[0].key").value(key));
        owner.client().get(org.path("/search?q=rep")).andExpect(jsonPath("$.projects[0].key").value("REP"));
        owner.client().get(org.path("/search?q=" + member.handle().substring(0, 6)))
                .andExpect(jsonPath("$.people[0].handle").value(member.handle()));
        owner.client().get(org.path("/search?q=%25")).andExpect(status().isOk()); // LIKE wildcards are escaped
    }

    private String data(Fixtures.User user, String dashboard, String widget) throws Exception {
        return user.client().get(org.path("/dashboards/" + dashboard + "/widgets/" + widget + "/data"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
}
