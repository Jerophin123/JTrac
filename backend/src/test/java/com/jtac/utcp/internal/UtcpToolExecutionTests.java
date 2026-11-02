package com.jtac.utcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jtac.DatabaseIntegrationTest;
import com.jtac.identity.UserDirectory;
import com.jtac.support.Fixtures;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

@DatabaseIntegrationTest
class UtcpToolExecutionTests {

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
    private Fixtures.Org org;
    private String issueKey;

    @BeforeEach
    void setUp() throws Exception {
        fixtures = new Fixtures(mvc, jdbc, users);
        alice = fixtures.user("Alice Test");
        org = fixtures.org(alice);
        fixtures.project(alice, org, "UTCP", "KANBAN");

        // Create an issue
        String issueBody = json.writeValueAsString(Map.of(
                "title", "First test issue for UTCP",
                "description", "Detailed description of the issue."
        ));
        MvcResult res = alice.client().post(org.path("/projects/UTCP/issues"), issueBody)
                .andExpect(status().isCreated())
                .andReturn();
        issueKey = JsonPath.read(res.getResponse().getContentAsString(), "$.key");

        // Add a comment
        String commentBody = json.writeValueAsString(Map.of("body", "Test comment for UTCP verification"));
        alice.client().post(org.path("/issues/" + issueKey + "/comments"), commentBody)
                .andExpect(status().isCreated());
    }

    @AfterEach
    void tearDown() {
        fixtures.close();
    }

    @Test
    void searchIssuesAndGetDetails() throws Exception {
        // 1. Search issues
        String searchBody = json.writeValueAsString(Map.of(
                "query", "First test issue",
                "projectKeys", List.of("UTCP"),
                "pageSize", 10
        ));
        MvcResult searchRes = alice.client().post("/api/v1/utcp/tools/search_issues", searchBody)
                .andExpect(status().isOk())
                .andReturn();

        List<?> items = JsonPath.read(searchRes.getResponse().getContentAsString(), "$.items");
        assertThat(items).isNotEmpty();
        int total = JsonPath.read(searchRes.getResponse().getContentAsString(), "$.totalCount");
        assertThat(total).isGreaterThanOrEqualTo(1);

        // 2. Get issue details
        String detailBody = json.writeValueAsString(Map.of("issueKey", issueKey));
        MvcResult detailRes = alice.client().post("/api/v1/utcp/tools/get_issue", detailBody)
                .andExpect(status().isOk())
                .andReturn();

        String key = JsonPath.read(detailRes.getResponse().getContentAsString(), "$.key");
        assertThat(key).isEqualTo(issueKey);
        String title = JsonPath.read(detailRes.getResponse().getContentAsString(), "$.title");
        assertThat(title).isEqualTo("First test issue for UTCP");

        // 3. Get issue status
        MvcResult statusRes = alice.client().post("/api/v1/utcp/tools/get_issue_status", detailBody)
                .andExpect(status().isOk())
                .andReturn();
        assertThat((String) JsonPath.read(statusRes.getResponse().getContentAsString(), "$.issueKey")).isEqualTo(issueKey);

        // 4. Get issue relationships
        MvcResult relRes = alice.client().post("/api/v1/utcp/tools/get_issue_relationships", detailBody)
                .andExpect(status().isOk())
                .andReturn();
        assertThat((String) JsonPath.read(relRes.getResponse().getContentAsString(), "$.issueKey")).isEqualTo(issueKey);

        // 5. Get issue history
        MvcResult histRes = alice.client().post("/api/v1/utcp/tools/get_issue_history", detailBody)
                .andExpect(status().isOk())
                .andReturn();
        assertThat((String) JsonPath.read(histRes.getResponse().getContentAsString(), "$.issueKey")).isEqualTo(issueKey);

        // 6. Get issue activity
        MvcResult actRes = alice.client().post("/api/v1/utcp/tools/get_issue_activity", detailBody)
                .andExpect(status().isOk())
                .andReturn();
        assertThat((String) JsonPath.read(actRes.getResponse().getContentAsString(), "$.issueKey")).isEqualTo(issueKey);
    }

    @Test
    void projectToolsWorkAsExpected() throws Exception {
        // 1. List projects
        MvcResult listRes = alice.client().post("/api/v1/utcp/tools/list_projects", "{}")
                .andExpect(status().isOk())
                .andReturn();
        List<?> projects = JsonPath.read(listRes.getResponse().getContentAsString(), "$.projects");
        assertThat(projects).isNotEmpty();

        // 2. Get project details
        String getProjBody = json.writeValueAsString(Map.of("projectKey", "UTCP"));
        MvcResult getRes = alice.client().post("/api/v1/utcp/tools/get_project", getProjBody)
                .andExpect(status().isOk())
                .andReturn();
        assertThat((String) JsonPath.read(getRes.getResponse().getContentAsString(), "$.project.key")).isEqualTo("UTCP");

        // 3. Get project metadata
        MvcResult metaRes = alice.client().post("/api/v1/utcp/tools/get_project_metadata", getProjBody)
                .andExpect(status().isOk())
                .andReturn();
        assertThat((String) JsonPath.read(metaRes.getResponse().getContentAsString(), "$.projectKey")).isEqualTo("UTCP");

        // 4. Get workflow
        MvcResult wfRes = alice.client().post("/api/v1/utcp/tools/get_workflow", getProjBody)
                .andExpect(status().isOk())
                .andReturn();
        assertThat((String) JsonPath.read(wfRes.getResponse().getContentAsString(), "$.workflow.projectKey")).isEqualTo("UTCP");

        // 5. Get available statuses
        MvcResult statRes = alice.client().post("/api/v1/utcp/tools/get_available_statuses", getProjBody)
                .andExpect(status().isOk())
                .andReturn();
        List<?> statuses = JsonPath.read(statRes.getResponse().getContentAsString(), "$.statuses");
        assertThat(statuses).isNotEmpty();

        // 6. Get permitted transitions
        String transBody = json.writeValueAsString(Map.of("issueKey", issueKey));
        MvcResult transRes = alice.client().post("/api/v1/utcp/tools/get_permitted_transitions", transBody)
                .andExpect(status().isOk())
                .andReturn();
        assertThat((String) JsonPath.read(transRes.getResponse().getContentAsString(), "$.issueKey")).isEqualTo(issueKey);
    }

    @Test
    void commentAndAttachmentToolsWorkAsExpected() throws Exception {
        // 1. List comments
        String listBody = json.writeValueAsString(Map.of("issueKey", issueKey));
        MvcResult listRes = alice.client().post("/api/v1/utcp/tools/list_issue_comments", listBody)
                .andExpect(status().isOk())
                .andReturn();

        List<Map<String, Object>> comments = JsonPath.read(listRes.getResponse().getContentAsString(), "$.comments");
        assertThat(comments).isNotEmpty();
        String commentId = comments.get(0).get("id").toString();

        // 2. Get comment details
        String getBody = json.writeValueAsString(Map.of("issueKey", issueKey, "commentId", commentId));
        MvcResult getRes = alice.client().post("/api/v1/utcp/tools/get_comment_details", getBody)
                .andExpect(status().isOk())
                .andReturn();

        assertThat((String) JsonPath.read(getRes.getResponse().getContentAsString(), "$.comment.body"))
                .contains("Test comment for UTCP verification");

        // 3. List attachments
        MvcResult attRes = alice.client().post("/api/v1/utcp/tools/list_issue_attachments", listBody)
                .andExpect(status().isOk())
                .andReturn();
        assertThat((List<?>) JsonPath.read(attRes.getResponse().getContentAsString(), "$.attachments")).isNotNull();
    }

    @Test
    void userAndReportingToolsWorkAsExpected() throws Exception {
        // 1. Get user info
        MvcResult userRes = alice.client().post("/api/v1/utcp/tools/get_user_info", "{}")
                .andExpect(status().isOk())
                .andReturn();
        assertThat((String) JsonPath.read(userRes.getResponse().getContentAsString(), "$.user.displayName"))
                .isEqualTo("Alice Test");

        // 2. List accessible teams
        MvcResult teamsRes = alice.client().post("/api/v1/utcp/tools/list_accessible_teams", "{}")
                .andExpect(status().isOk())
                .andReturn();
        assertThat((List<?>) JsonPath.read(teamsRes.getResponse().getContentAsString(), "$.teams")).isNotNull();

        // 3. Get project activity
        String projBody = json.writeValueAsString(Map.of("projectKey", "UTCP"));
        MvcResult projActRes = alice.client().post("/api/v1/utcp/tools/get_project_activity", projBody)
                .andExpect(status().isOk())
                .andReturn();
        assertThat((String) JsonPath.read(projActRes.getResponse().getContentAsString(), "$.projectKey")).isEqualTo("UTCP");

        // 4. Get issue statistics
        MvcResult statRes = alice.client().post("/api/v1/utcp/tools/get_issue_statistics", projBody)
                .andExpect(status().isOk())
                .andReturn();
        assertThat((String) JsonPath.read(statRes.getResponse().getContentAsString(), "$.projectKey")).isEqualTo("UTCP");

        // 5. List fix reports
        String fixBody = json.writeValueAsString(Map.of("issueKey", issueKey));
        MvcResult reportRes = alice.client().post("/api/v1/utcp/tools/list_fix_reports", fixBody)
                .andExpect(status().isOk())
                .andReturn();
        assertThat((List<?>) JsonPath.read(reportRes.getResponse().getContentAsString(), "$.reports")).isNotNull();
    }

    @Test
    void unifiedExecuteEndpointWorks() throws Exception {
        String execBody = json.writeValueAsString(Map.of(
                "tool", "get_project",
                "arguments", Map.of("projectKey", "UTCP")
        ));
        MvcResult execRes = alice.client().post("/api/v1/utcp/execute", execBody)
                .andExpect(status().isOk())
                .andReturn();

        assertThat((String) JsonPath.read(execRes.getResponse().getContentAsString(), "$.project.key")).isEqualTo("UTCP");
    }
}
