package com.jtac.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jtac.DatabaseIntegrationTest;
import com.jtac.identity.UserDirectory;
import com.jtac.support.Fixtures;
import com.jtac.support.TestClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Cross-tenant isolation: a member of one organization can neither read nor change anything
 * in another, whether addressing it by slug, by issue key or by raw ids under their own
 * organization's path. The database rejects cross-tenant references even if application
 * code were to construct one.
 */
@DatabaseIntegrationTest
class TenantIsolationTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private UserDirectory users;

    private Fixtures fx;
    private Fixtures.User alice;
    private Fixtures.User mallory;
    private Fixtures.Org orgA;
    private Fixtures.Org orgB;
    private String issueKey;
    private String viewId;
    private String dashboardId;
    private String webhookId;
    private String commentId;
    private String attachmentId;

    @BeforeEach
    void setUp() throws Exception {
        fx = new Fixtures(mvc, jdbc, users);
        alice = fx.user("Alice");
        mallory = fx.user("Mallory");
        orgA = fx.org(alice);
        orgB = fx.org(mallory);
        fx.project(alice, orgA, "ALPHA", "KANBAN");
        fx.project(mallory, orgB, "BETA", "KANBAN");
        issueKey = fx.issue(alice, orgA, "ALPHA", "{\"title\":\"Secret plan\",\"description\":\"confidential\"}");

        commentId = JsonPath.read(alice.client().post(orgA.path("/issues/" + issueKey + "/comments"), "{\"body\":\"internal note\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        MockMultipartFile file = new MockMultipartFile("file", "plan.txt", "text/plain", "top secret".getBytes());
        attachmentId = JsonPath.read(alice.client().perform(
                        MockMvcRequestBuilders.multipart(orgA.path("/issues/" + issueKey + "/attachments")).file(file), HttpMethod.POST)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        viewId = JsonPath.read(alice.client().post(orgA.path("/views"), "{\"name\":\"Mine\",\"shared\":true}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        dashboardId = JsonPath.read(alice.client().post(orgA.path("/dashboards"), "{\"name\":\"Board\",\"shared\":true}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        webhookId = JsonPath.read(alice.client().post(orgA.path("/webhooks"),
                        "{\"url\":\"http://127.0.0.1:9/hook\",\"events\":[\"issue.created\"]}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.webhook.id");
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    @Test
    void anotherOrganizationIsInvisibleBySlug() throws Exception {
        TestClient m = mallory.client();
        for (String path : List.of("", "/members", "/projects", "/projects/ALPHA", "/projects/ALPHA/catalog",
                "/projects/ALPHA/workflow", "/projects/ALPHA/fields", "/issues/" + issueKey,
                "/issues/" + issueKey + "/comments", "/issues/" + issueKey + "/activity",
                "/issues/" + issueKey + "/attachments", "/issues/" + issueKey + "/attachments/" + attachmentId + "/content",
                "/views", "/views/" + viewId, "/dashboards", "/dashboards/" + dashboardId, "/webhooks",
                "/webhooks/" + webhookId, "/notifications", "/audit", "/teams", "/invitations", "/search?q=Secret")) {
            m.get(orgA.path(path)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value("urn:jtac:problem:not-found"));
        }
        m.patch(orgA.path("/issues/" + issueKey), "{\"changes\":{\"title\":\"pwned\"},\"version\":0}")
                .andExpect(status().isNotFound());
        m.post(orgA.path("/issues/" + issueKey + "/comments"), "{\"body\":\"hi\"}").andExpect(status().isNotFound());
        m.post(orgA.path("/projects/ALPHA/issues"), "{\"title\":\"x\"}").andExpect(status().isNotFound());
        m.delete(orgA.path("/issues/" + issueKey)).andExpect(status().isNotFound());
        m.post(orgA.path("/issues/search"), "{}").andExpect(status().isNotFound());
        m.post(orgA.path("/issues/export"), "{}").andExpect(status().isNotFound());
        m.post(orgA.path("/invitations"), "{\"email\":\"x@jtac.test\",\"role\":\"OWNER\"}").andExpect(status().isNotFound());
    }

    @Test
    void foreignIdsUnderTheCallersOwnOrganizationAreNotFound() throws Exception {
        TestClient m = mallory.client();
        m.get(orgB.path("/issues/" + issueKey)).andExpect(status().isNotFound());
        m.get(orgB.path("/projects/ALPHA")).andExpect(status().isNotFound());
        m.get(orgB.path("/views/" + viewId)).andExpect(status().isNotFound());
        m.put(orgB.path("/views/" + viewId), "{\"version\":0,\"view\":{\"name\":\"x\"}}").andExpect(status().isNotFound());
        m.delete(orgB.path("/views/" + viewId)).andExpect(status().isNotFound());
        m.get(orgB.path("/dashboards/" + dashboardId)).andExpect(status().isNotFound());
        m.delete(orgB.path("/dashboards/" + dashboardId)).andExpect(status().isNotFound());
        m.get(orgB.path("/webhooks/" + webhookId)).andExpect(status().isNotFound());
        m.delete(orgB.path("/webhooks/" + webhookId)).andExpect(status().isNotFound());
        m.post(orgB.path("/webhooks/" + webhookId + "/rotate-secret"), null).andExpect(status().isNotFound());
        m.delete(orgB.path("/issues/" + issueKey + "/comments/" + commentId)).andExpect(status().isNotFound());

        // Search and aggregates in the caller's organization never include the other tenant's issues.
        m.post(orgB.path("/issues/search"), "{\"filter\":{\"projectKeys\":[\"ALPHA\"],\"text\":\"Secret\"}}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        m.get(orgB.path("/search?q=" + issueKey)).andExpect(status().isOk())
                .andExpect(jsonPath("$.issues.length()").value(0));

        // Linking to another tenant's issue by key fails as if it did not exist.
        String own = fx.issue(mallory, orgB, "BETA", "{\"title\":\"mine\"}");
        m.post(orgB.path("/issues/" + own + "/links"), "{\"targetKey\":\"" + issueKey + "\",\"type\":\"RELATES\"}")
                .andExpect(status().isNotFound());
        // Assigning another tenant's user is rejected.
        String version = JsonPath.read(m.get(orgB.path("/issues/" + own)).andReturn().getResponse().getContentAsString(), "$.version").toString();
        m.patch(orgB.path("/issues/" + own), "{\"changes\":{\"assigneeId\":\"" + alice.id() + "\"},\"version\":" + version + "}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void membersSeeOrganizationProjectsButNotPrivateOnesAndGuestsSeeNothingUninvited() throws Exception {
        Fixtures.User member = fx.user("Member");
        Fixtures.User guest = fx.user("Guest");
        fx.member(orgA, member, "MEMBER");
        fx.member(orgA, guest, "GUEST");
        fx.project(alice, orgA, "PRIV", "KANBAN", "PRIVATE");
        String privateIssue = fx.issue(alice, orgA, "PRIV", "{\"title\":\"Private\"}");

        member.client().get(orgA.path("/projects/ALPHA")).andExpect(status().isOk());
        member.client().get(orgA.path("/issues/" + issueKey)).andExpect(status().isOk());
        member.client().get(orgA.path("/projects/PRIV")).andExpect(status().isNotFound());
        member.client().get(orgA.path("/issues/" + privateIssue)).andExpect(status().isNotFound());
        member.client().post(orgA.path("/issues/search"), "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].key").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(privateIssue))));

        guest.client().get(orgA.path("/projects")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        guest.client().get(orgA.path("/issues/" + issueKey)).andExpect(status().isNotFound());
        guest.client().get(orgA.path("/members")).andExpect(status().isOk());

        // Explicit project membership grants the guest access to exactly that project.
        String viewerRole = JsonPath.read(alice.client().get(orgA.path("/projects/ALPHA/catalog")).andReturn().getResponse()
                .getContentAsString(), "$.roles[?(@.systemKey=='VIEWER')].id").toString().replaceAll("[\\[\\]\"]", "");
        alice.client().put(orgA.path("/projects/ALPHA/members/" + guest.id()), "{\"roleId\":\"" + viewerRole + "\"}")
                .andExpect(status().isNoContent());
        guest.client().get(orgA.path("/issues/" + issueKey)).andExpect(status().isOk());
        guest.client().patch(orgA.path("/issues/" + issueKey), "{\"changes\":{\"title\":\"x\"},\"version\":0}")
                .andExpect(status().isForbidden());
        guest.client().get(orgA.path("/projects/PRIV")).andExpect(status().isNotFound());
    }

    @Test
    void apiTokensRestrictedToAnOrganizationCannotReachAnother() throws Exception {
        fx.member(orgB, alice, "MEMBER");
        String body = alice.client().post("/api/v1/me/tokens",
                        "{\"name\":\"ci\",\"scopes\":[\"read\",\"write\"],\"organizationId\":\"" + orgB.id() + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        TestClient token = TestClient.withToken(mvc, JsonPath.read(body, "$.secret"));
        token.get(orgB.path("/projects")).andExpect(status().isOk());
        token.get(orgA.path("/projects")).andExpect(status().isNotFound());
        token.get(orgA.path("/issues/" + issueKey)).andExpect(status().isNotFound());
        // Tokens cannot mint tokens.
        token.post("/api/v1/me/tokens", "{\"name\":\"x\",\"scopes\":[\"read\"]}").andExpect(status().isForbidden());
    }

    @Test
    void theDatabaseRejectsCrossTenantReferences() {
        UUID issueA = jdbc.sql("SELECT i.id FROM issue i JOIN project p ON p.id = i.project_id WHERE p.organization_id = :org")
                .param("org", orgA.id()).query(UUID.class).single();
        UUID projectB = jdbc.sql("SELECT id FROM project WHERE organization_id = :org").param("org", orgB.id())
                .query(UUID.class).single();
        // A comment that claims tenant B but points at tenant A's issue.
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO comment (id, organization_id, issue_id, author_id, body)
                VALUES (:id, :org, :issue, :author, 'x')
                """).param("id", UUID.randomUUID()).param("org", orgB.id()).param("issue", issueA)
                .param("author", mallory.id()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        // A saved view of tenant B scoped to a project... of tenant B is fine; to tenant A's project is not.
        UUID projectA = jdbc.sql("SELECT id FROM project WHERE organization_id = :org").param("org", orgA.id())
                .query(UUID.class).single();
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO saved_view (id, organization_id, project_id, owner_id, name, kind)
                VALUES (:id, :org, :project, :owner, 'x', 'LIST')
                """).param("id", UUID.randomUUID()).param("org", orgB.id()).param("project", projectA)
                .param("owner", mallory.id()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        // An issue assigned to someone who is not a member of the issue's organization.
        assertThatThrownBy(() -> jdbc.sql("UPDATE issue SET assignee_id = :user WHERE id = :issue")
                .param("user", mallory.id()).param("issue", issueA).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(projectB).isNotNull();
    }

    @Test
    void ownerCanCustomizeColorAndLogoButAdminCannot() throws Exception {
        Fixtures.User adminBob = fx.user("Bob");
        fx.member(orgA, adminBob, "ADMIN");

        // Alice (OWNER) can customize color and logo
        String updateJson = """
                {
                  "name": "Updated Org A",
                  "version": 0,
                  "settings": {
                    "color": "#2563eb",
                    "logoUrl": "data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=",
                    "allowedEmailDomains": [],
                    "defaultProjectVisibility": "ORGANIZATION",
                    "membersCanCreateProjects": true
                  }
                }
                """;
        alice.client().patch(orgA.path(""), updateJson)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.color").value("#2563eb"))
                .andExpect(jsonPath("$.settings.logoUrl").value("data:image/svg+xml;base64,PHN2Zz48L3N2Zz4="));

        // GET /api/v1/orgs reflects custom color and logoUrl
        alice.client().get("/api/v1/orgs")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.slug=='" + orgA.slug() + "')].color").value("#2563eb"))
                .andExpect(jsonPath("$[?(@.slug=='" + orgA.slug() + "')].logoUrl").value("data:image/svg+xml;base64,PHN2Zz48L3N2Zz4="));

        // Bob (ADMIN, but not OWNER) attempting to change color is rejected with 403 Forbidden
        String bobUpdateJson = """
                {
                  "name": "Updated Org A",
                  "version": 1,
                  "settings": {
                    "color": "#16a34a",
                    "logoUrl": "data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=",
                    "allowedEmailDomains": [],
                    "defaultProjectVisibility": "ORGANIZATION",
                    "membersCanCreateProjects": true
                  }
                }
                """;
        adminBob.client().patch(orgA.path(""), bobUpdateJson)
                .andExpect(status().isForbidden());

        // Invalid hex color is rejected with 400 Bad Request
        String invalidColorJson = """
                {
                  "name": "Updated Org A",
                  "version": 1,
                  "settings": {
                    "color": "not-a-color",
                    "allowedEmailDomains": [],
                    "defaultProjectVisibility": "ORGANIZATION",
                    "membersCanCreateProjects": true
                  }
                }
                """;
        alice.client().patch(orgA.path(""), invalidColorJson)
                .andExpect(status().isBadRequest());
    }
}
