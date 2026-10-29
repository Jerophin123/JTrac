package com.jtac.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jtac.DatabaseIntegrationTest;
import com.jtac.identity.UserDirectory;
import com.jtac.support.Fixtures;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.json.JsonMapper;

/** The per-connection request limit and the anonymous registration limit (own context with low limits). */
@DatabaseIntegrationTest
@TestPropertySource(properties = {"jtac.mcp.requests-per-minute=3", "jtac.mcp.registrations-per-hour=4"})
class McpRateLimitTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private UserDirectory users;

    @Autowired
    private JsonMapper json;

    private Fixtures fx;
    private AgentHarness agent;

    @AfterEach
    void tearDown() {
        if (fx != null) {
            fx.close();
        }
        if (agent != null) {
            agent.close();
        }
    }

    @Test
    void connectionsAreRateLimitedAndRegistrationIsThrottled() throws Exception {
        fx = new Fixtures(mvc, jdbc, users);
        agent = new AgentHarness(mvc, json, jdbc);
        Fixtures.User owner = fx.user("Owner");
        Fixtures.Org org = fx.org(owner);
        AgentHarness.Connection c = agent.createConnection(owner, org, List.of());
        AgentHarness.Tokens t = agent.signIn(owner, c, null); // registration 1
        String list = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
        for (int i = 0; i < 3; i++) {
            assertThat(agent.mcp(c.publicId(), t.access(), list).getResponse().getStatus()).isEqualTo(200);
        }
        MvcResult limited = agent.mcp(c.publicId(), t.access(), list);
        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(limited.getResponse().getHeader("Retry-After")).isNotBlank();
        assertThat(jdbc.sql("SELECT count(*) FROM mcp_activity WHERE connection_id = :c AND outcome = 'RATE_LIMITED'")
                .param("c", UUID.fromString(c.id())).query(Long.class).single()).isEqualTo(1);

        for (int i = 0; i < 3; i++) {
            agent.register(AgentHarness.REDIRECT); // registrations 2 to 4
        }
        mvc.perform(MockMvcRequestBuilders.post("/oauth/register").contentType("application/json")
                        .content("{\"redirect_uris\":[\"http://localhost:1/cb\"]}"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error").value("slow_down"));
    }
}
