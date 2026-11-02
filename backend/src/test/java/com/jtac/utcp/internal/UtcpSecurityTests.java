package com.jtac.utcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jtac.DatabaseIntegrationTest;
import com.jtac.identity.UserDirectory;
import com.jtac.support.Fixtures;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.json.JsonMapper;

@DatabaseIntegrationTest
class UtcpSecurityTests {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private UserDirectory users;
    @Autowired
    private JsonMapper json;

    private Fixtures fixtures;
    private Fixtures.User alice;
    private Fixtures.User bob;
    private Fixtures.Org orgA;
    private Fixtures.Org orgB;
    private String issueKeyA;

    @BeforeEach
    void setUp() throws Exception {
        fixtures = new Fixtures(mvc, jdbc, users);
        alice = fixtures.user("Alice Alpha");
        bob = fixtures.user("Bob Beta");

        orgA = fixtures.org(alice);
        orgB = fixtures.org(bob);

        fixtures.project(alice, orgA, "ALPHA", "KANBAN");
        fixtures.project(bob, orgB, "BETA", "KANBAN");

        String issueBody = json.writeValueAsString(Map.of(
                "title", "Alpha confidential issue",
                "description", "Sensitive information for Org Alpha only."
        ));
        MvcResult res = alice.client().post(orgA.path("/projects/ALPHA/issues"), issueBody)
                .andExpect(status().isCreated())
                .andReturn();
        issueKeyA = JsonPath.read(res.getResponse().getContentAsString(), "$.key");
    }

    @AfterEach
    void tearDown() {
        fixtures.close();
    }

    @Test
    void unauthenticatedExecutionIsRejected() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/utcp/tools/search_issues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tenantIsolationPreventsCrossTenantDataAccess() throws Exception {
        // Bob belongs only to Org Beta. Bob tries to query Alice's issue ALPHA-1 via UTCP
        String getIssueBody = json.writeValueAsString(Map.of("issueKey", issueKeyA));

        // 1. Bob queries without header -> defaults to Bob's org (Org Beta) where ALPHA does not exist -> Not Found / 404
        bob.client().post("/api/v1/utcp/tools/get_issue", getIssueBody)
                .andExpect(status().isNotFound());

        // 2. Bob attempts to spoof Org Alpha header -> Bob is not a member of Org Alpha -> 404 Not Found (tenant enumeration defense)
        bob.client().perform(
                MockMvcRequestBuilders.post("/api/v1/utcp/tools/get_issue")
                        .header("X-JTRAC-Organization", orgA.slug())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(getIssueBody),
                HttpMethod.POST
        ).andExpect(status().isNotFound());
    }

    @Test
    void disablingUtcpRejectsExecutionAndPreservesDataAndAudit() throws Exception {
        // Alice executes search_issues -> succeeds
        alice.client().post("/api/v1/utcp/tools/search_issues", "{}")
                .andExpect(status().isOk());

        // Alice checks activity log
        MvcResult actRes1 = alice.client().get("/api/v1/me/utcp/activity")
                .andExpect(status().isOk())
                .andReturn();
        int initialCount = JsonPath.read(actRes1.getResponse().getContentAsString(), "$.total");
        assertThat(initialCount).isGreaterThanOrEqualTo(1);

        // Alice disables UTCP
        alice.client().post("/api/v1/me/utcp/disable", "{}")
                .andExpect(status().isOk());

        // Check settings state
        MvcResult setRes = alice.client().get("/api/v1/me/utcp/settings")
                .andExpect(status().isOk())
                .andReturn();
        assertThat((String) JsonPath.read(setRes.getResponse().getContentAsString(), "$.state")).isEqualTo("DISABLED");
        assertThat((Boolean) JsonPath.read(setRes.getResponse().getContentAsString(), "$.enabled")).isFalse();

        // Alice attempts tool execution -> Rejected with 403 Forbidden
        alice.client().post("/api/v1/utcp/tools/search_issues", "{}")
                .andExpect(status().isForbidden());

        // Normal JTRAC web app operation is completely unaffected
        alice.client().get(orgA.path("/issues/" + issueKeyA))
                .andExpect(status().isOk());

        // Re-enable UTCP
        alice.client().post("/api/v1/me/utcp/enable", "{}")
                .andExpect(status().isOk());

        // Execution works again
        alice.client().post("/api/v1/utcp/tools/search_issues", "{}")
                .andExpect(status().isOk());

        // Audit events are preserved and increased
        MvcResult actRes2 = alice.client().get("/api/v1/me/utcp/activity")
                .andExpect(status().isOk())
                .andReturn();
        int finalCount = JsonPath.read(actRes2.getResponse().getContentAsString(), "$.total");
        assertThat(finalCount).isGreaterThan(initialCount);
    }
}
