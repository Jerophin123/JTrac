package com.jtac.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jtac.DatabaseIntegrationTest;
import java.sql.SQLException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Health probes, security headers, request correlation and, most importantly, the
 * least-privilege database connection: the running application is jtac_app and cannot
 * change the schema or the migration history.
 */
@DatabaseIntegrationTest
class PlatformIntegrationTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void readinessAndLivenessProbesArePublicAndUp() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void healthDoesNotExposeDetails() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void otherActuatorEndpointsAreNotReachable() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/beans")).andExpect(status().isUnauthorized());
    }

    @Test
    void responsesCarrySecurityHeaders() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("frame-ancestors 'none'")));
    }

    @Test
    void safeRequestIdsAreEchoedAndUnsafeOnesReplaced() throws Exception {
        mvc.perform(get("/actuator/health").header("X-Request-Id", "client-abc-12345"))
                .andExpect(header().string("X-Request-Id", "client-abc-12345"));
        String replaced = mvc.perform(get("/actuator/health").header("X-Request-Id", "bad id\r\ninjected"))
                .andReturn().getResponse().getHeader("X-Request-Id");
        assertThat(replaced).isNotNull().doesNotContain("injected").hasSize(36);
    }

    @Test
    void applicationConnectsAsTheLeastPrivilegedRole() {
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo("jtac_app");
        assertThat(jdbc.sql("SELECT current_schema()").query(String.class).single()).isEqualTo("jtac");
    }

    @Test
    void applicationRoleCannotChangeTheSchema() {
        assertInsufficientPrivilege(() -> jdbc.sql("CREATE TABLE should_not_exist (id int)").update());
        assertInsufficientPrivilege(() -> jdbc.sql("ALTER TABLE user_account ADD COLUMN should_not_exist int").update());
        assertInsufficientPrivilege(() -> jdbc.sql("DROP TABLE user_account").update());
    }

    @Test
    void applicationRoleCanReadButNotRewriteMigrationHistory() {
        assertThat(jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE success").query(Long.class).single())
                .isPositive();
        assertInsufficientPrivilege(() -> jdbc.sql("DELETE FROM flyway_schema_history").update());
        assertInsufficientPrivilege(() -> jdbc.sql("UPDATE flyway_schema_history SET success = false").update());
    }

    /** PostgreSQL SQLSTATE 42501 = insufficient_privilege (covers "permission denied" and "must be owner"). */
    private static void assertInsufficientPrivilege(ThrowingCallable statement) {
        assertThatThrownBy(statement)
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .isInstanceOfSatisfying(SQLException.class,
                        cause -> assertThat(cause.getSQLState()).isEqualTo("42501"));
    }
}
