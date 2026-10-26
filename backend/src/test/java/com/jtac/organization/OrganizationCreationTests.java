package com.jtac.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jtac.DatabaseIntegrationTest;
import com.jtac.identity.UserDirectory;
import com.jtac.support.Fixtures;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** Only a server administrator may create organizations; everyone else joins by invitation. */
@DatabaseIntegrationTest
class OrganizationCreationTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private UserDirectory users;

    private Fixtures fx;

    @BeforeEach
    void setUp() {
        fx = new Fixtures(mvc, jdbc, users);
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    @Test
    void regularUserCannotCreateAnOrganization() throws Exception {
        Fixtures.User user = fx.user("Regular");
        String slug = uniqueSlug();

        user.client().get("/api/v1/auth/session")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.serverAdmin").value(false));
        user.client().post("/api/v1/orgs", "{\"name\":\"Rogue\",\"slug\":\"" + slug + "\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:jtac:problem:forbidden"));

        Long count = jdbc.sql("SELECT count(*) FROM organization WHERE slug = :slug").param("slug", slug)
                .query(Long.class).single();
        assertThat(count).isZero();
    }

    @Test
    void serverAdministratorCanCreateAnOrganization() throws Exception {
        Fixtures.User admin = fx.user("Server Admin");
        fx.serverAdmin(admin);
        String slug = uniqueSlug();

        admin.client().get("/api/v1/auth/session")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.serverAdmin").value(true));
        String body = admin.client().post("/api/v1/orgs", "{\"name\":\"Allowed\",\"slug\":\"" + slug + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andReturn().getResponse().getContentAsString();

        jdbc.sql("DELETE FROM organization WHERE id = :id").param("id", UUID.fromString(JsonPath.read(body, "$.id"))).update();
    }

    private static String uniqueSlug() {
        return "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
