package com.jtac.system;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jtac.DatabaseIntegrationTest;
import com.jtac.identity.UserDirectory;
import com.jtac.support.Fixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** The About page's version: every signed-in user, but nobody anonymous (no fingerprinting). */
@DatabaseIntegrationTest
class AboutInfoTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private UserDirectory users;

    private Fixtures fx;
    private Fixtures.User member;

    @BeforeEach
    void setUp() throws Exception {
        fx = new Fixtures(mvc, jdbc, users);
        member = fx.user("About Reader");
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    @Test
    void anySignedInUserSeesTheVersion() throws Exception {
        member.client().get("/api/v1/system/about")
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.version").isString())
                .andExpect(jsonPath("$.buildTime").exists());
    }

    @Test
    void anonymousVisitorsAreRefused() throws Exception {
        mvc.perform(get("/api/v1/system/about")).andExpect(status().isUnauthorized());
    }

    @Test
    void theDetailedServerInfoStaysAdministratorOnly() throws Exception {
        member.client().get("/api/v1/system/info").andExpect(status().isForbidden());
    }
}
