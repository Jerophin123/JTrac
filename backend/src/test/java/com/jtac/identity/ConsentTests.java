package com.jtac.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jtac.DatabaseIntegrationTest;
import com.jtac.support.Fixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** Consent to the Terms of Service and Privacy Notice: required, recorded per version, withdrawable. */
@DatabaseIntegrationTest
class ConsentTests {

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
    void publicConfigCarriesTheLegalDetails() throws Exception {
        Fixtures.User user = fx.user("Reader");
        user.client().get("/api/v1/config")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legal.version").isNotEmpty())
                .andExpect(jsonPath("$.legal.effectiveDate").isNotEmpty());
    }

    @Test
    void consentIsRequiredUntilAcceptedAndAgainAfterWithdrawal() throws Exception {
        Fixtures.User user = fx.user("Consenting");

        user.client().get("/api/v1/auth/session").andExpect(jsonPath("$.consentRequired").value(true));
        user.client().get("/api/v1/me/consent")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.history").isEmpty());

        user.client().post("/api/v1/me/consent", "").andExpect(status().isNoContent());
        user.client().post("/api/v1/me/consent", "").andExpect(status().isNoContent()); // idempotent

        user.client().get("/api/v1/auth/session").andExpect(jsonPath("$.consentRequired").value(false));
        user.client().get("/api/v1/me/consent")
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.history.length()").value(1));

        user.client().post("/api/v1/me/consent/withdraw", "{\"reason\":\"Leaving the team\"}").andExpect(status().isAccepted());

        user.client().get("/api/v1/auth/session").andExpect(jsonPath("$.consentRequired").value(true));
        user.client().get("/api/v1/me/consent")
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.history[0].withdrawnAt").isNotEmpty());
        Long acknowledgements = jdbc.sql("SELECT count(*) FROM email_outbox WHERE to_address = :to AND subject = 'We received your erasure request'")
                .param("to", user.email()).query(Long.class).single();
        assertThat(acknowledgements).isEqualTo(1);
        jdbc.sql("DELETE FROM email_outbox WHERE to_address = :to").param("to", user.email()).update();
    }
}
