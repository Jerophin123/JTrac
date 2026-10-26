package com.jtac.support;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jtac.identity.UserDirectory;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Creates users, organizations and projects for integration tests through the public API
 * (plus direct membership rows where the invitation flow is not under test), and removes
 * everything it created afterwards. Audit events are append-only for the application role
 * and therefore remain, as in production.
 */
public final class Fixtures implements AutoCloseable {

    private final MockMvc mvc;
    private final JdbcClient jdbc;
    private final UserDirectory users;
    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdOrgs = new ArrayList<>();

    public Fixtures(MockMvc mvc, JdbcClient jdbc, UserDirectory users) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.users = users;
    }

    /** A verified user with a signed-in client. */
    public record User(UUID id, String email, String password, String handle, TestClient client) {
    }

    public record Org(UUID id, String slug) {
        public String path(String suffix) {
            return "/api/v1/orgs/" + slug + suffix;
        }
    }

    public User user(String name) throws Exception {
        String email = name.toLowerCase().replace(' ', '.') + "-" + UUID.randomUUID() + "@jtac.test";
        String password = "Pw-" + UUID.randomUUID(); // generated per test, never logged
        UUID id = users.createVerifiedAccount(email, name, password);
        createdUsers.add(id);
        TestClient client = new TestClient(mvc);
        client.login(email, password).andExpect(status().isOk());
        String handle = users.find(id).orElseThrow().handle();
        return new User(id, email, password, handle, client);
    }

    /** Makes {@code user} a server administrator, the only kind of account that may create organizations. */
    public void serverAdmin(User user) {
        jdbc.sql("UPDATE user_account SET server_admin = true WHERE id = :id").param("id", user.id()).update();
    }

    /** Creates an organization owned by {@code owner}, who is made a server administrator to create it. */
    public Org org(User owner) throws Exception {
        serverAdmin(owner);
        String slug = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String body = owner.client().post("/api/v1/orgs", "{\"name\":\"Test " + slug + "\",\"slug\":\"" + slug + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
        createdOrgs.add(id);
        return new Org(id, JsonPath.read(body, "$.slug"));
    }

    /** Adds a member directly (OWNER, ADMIN, MEMBER or GUEST). */
    public void member(Org org, User user, String role) {
        jdbc.sql("INSERT INTO membership (organization_id, user_id, role) VALUES (:org, :user, :role)")
                .param("org", org.id()).param("user", user.id()).param("role", role).update();
    }

    /** Creates a project (template KANBAN or STRUCTURED) and returns its key. */
    public String project(User admin, Org org, String key, String template) throws Exception {
        return project(admin, org, key, template, "ORGANIZATION");
    }

    public String project(User admin, Org org, String key, String template, String visibility) throws Exception {
        admin.client().post(org.path("/projects"), "{\"key\":\"" + key + "\",\"name\":\"Project " + key
                + "\",\"template\":\"" + template + "\",\"visibility\":\"" + visibility + "\"}")
                .andExpect(status().isCreated());
        return key;
    }

    /** Creates an issue and returns its key. */
    public String issue(User user, Org org, String projectKey, String json) throws Exception {
        String body = user.client().post(org.path("/projects/" + projectKey + "/issues"), json)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.key");
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    @Override
    public void close() {
        for (UUID org : createdOrgs) {
            deleteWithRetry("DELETE FROM organization WHERE id = :id", org);
        }
        for (UUID user : createdUsers) {
            deleteWithRetry("DELETE FROM user_account WHERE id = :id", user);
        }
    }

    private void deleteWithRetry(String sql, UUID id) {
        int maxAttempts = 5;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                jdbc.sql(sql).param("id", id).update();
                return;
            } catch (TransientDataAccessException e) {
                if (i == maxAttempts - 1) {
                    throw e;
                }
                try {
                    Thread.sleep(50L * (i + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
