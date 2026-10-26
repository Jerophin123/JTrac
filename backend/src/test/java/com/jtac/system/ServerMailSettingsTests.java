package com.jtac.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jtac.DatabaseIntegrationTest;
import com.jtac.identity.UserDirectory;
import com.jtac.mail.internal.MailDispatcher;
import com.jtac.support.Fixtures;
import com.jtrac.serverconfig.FilePermissions;
import com.jtrac.serverconfig.testing.TestSmtpServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Server email settings: server administrators only, validated with the shared rules, saved
 * to the installation's config files (password only in the owner-only secrets file), applied
 * without a restart, and testable against a controlled SMTP server.
 */
@DatabaseIntegrationTest
@TestPropertySource(properties = "jtrac.config-dir=build/test-config/server-mail")
class ServerMailSettingsTests {

    private static final Path CONFIG = Path.of("build/test-config/server-mail");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private UserDirectory users;

    @Autowired
    private MailDispatcher dispatcher;

    private Fixtures fx;
    private Fixtures.User admin;
    private Fixtures.User member;

    /** Leftover files from an earlier run would be imported when this test's context starts. */
    @BeforeAll
    static void removeLeftoverConfig() throws IOException {
        if (Files.isDirectory(CONFIG)) {
            try (var files = Files.list(CONFIG)) {
                for (Path file : files.toList()) {
                    Files.delete(file);
                }
            }
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        fx = new Fixtures(mvc, jdbc, users);
        admin = fx.user("Server Admin");
        fx.serverAdmin(admin);
        member = fx.user("Member");
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    private static String body(boolean enabled, String host, int port, String tls, boolean auth, String trusted, String password) {
        String settings = """
                {"enabled":%s,"host":"%s","port":%d,"auth":%s,"username":"jtrac@example.test","tls":"%s",
                 "fromAddress":"jtrac@example.test","fromName":"JTRAC","replyTo":"","timeoutSeconds":5,"maxAttempts":3,
                 "trustedCertificates":"%s"}""".formatted(enabled, host, port, auth, tls, trusted.replace("\\", "\\\\"));
        return password == null ? "{\"settings\":" + settings + "}" : "{\"settings\":" + settings + ",\"password\":\"" + password + "\"}";
    }

    @Test
    void onlyServerAdministratorsCanSeeOrChangeEmailSettings() throws Exception {
        member.client().get("/api/v1/system/mail").andExpect(status().isForbidden());
        member.client().put("/api/v1/system/mail", body(false, "", 587, "STARTTLS", true, "", null)).andExpect(status().isForbidden());
        member.client().post("/api/v1/system/mail/test", "{\"settings\":{\"enabled\":true,\"host\":\"x\",\"port\":587,\"auth\":false,"
                + "\"username\":\"\",\"tls\":\"STARTTLS\",\"fromAddress\":\"a@b.c\",\"fromName\":\"\",\"replyTo\":\"\",\"timeoutSeconds\":5,"
                + "\"maxAttempts\":3,\"trustedCertificates\":\"\"},\"recipient\":\"x@y.z\"}").andExpect(status().isForbidden());

        admin.client().get("/api/v1/system/mail")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.host").isString())
                .andExpect(jsonPath("$.settings.tls").isString())
                .andExpect(jsonPath("$.settings.password").doesNotExist())
                .andExpect(jsonPath("$.outbox.pending").isNumber());
    }

    @Test
    void rejectsInvalidSettingsWithFieldErrors() throws Exception {
        admin.client().put("/api/v1/system/mail", body(true, "smtp.example.com", 25, "NONE", true, "", "secret"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:jtac:problem:validation-failed"))
                .andExpect(jsonPath("$.errors[?(@.field == 'smtp.tls')]").exists());
    }

    @Test
    void sendsATestEmailThroughTheControlledServer() throws Exception {
        try (TestSmtpServer smtp = TestSmtpServer.builder().mode(TestSmtpServer.Mode.STARTTLS).start()) {
            String request = body(true, "localhost", smtp.port(), "STARTTLS", true, smtp.certificatePem().toString(), "correct-horse");
            request = request.substring(0, request.length() - 1) + ",\"recipient\":\"ops@example.test\"}";

            String response = admin.client().post("/api/v1/system/mail/test", request)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accepted").value(true))
                    .andReturn().getResponse().getContentAsString();

            assertThat(response).doesNotContain("correct-horse");
            assertThat(smtp.received()).hasSize(1);
            assertThat(smtp.received().getFirst().overTls()).isTrue();
        }
    }

    @Test
    void savesSettingsToTheConfigFilesAndAppliesThem() throws Exception {
        admin.client().put("/api/v1/system/mail", body(true, "smtp.example.com", 587, "STARTTLS", true, "", "s3cret-pass"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mail.settings.host").value("smtp.example.com"))
                .andExpect(jsonPath("$.mail.passwordSet").value(true));

        String settings = Files.readString(CONFIG.resolve("jtrac.properties"), StandardCharsets.ISO_8859_1);
        String secrets = Files.readString(CONFIG.resolve("secrets.properties"), StandardCharsets.ISO_8859_1);
        assertThat(settings).contains("jtac.mail.smtp.host=smtp.example.com").doesNotContain("s3cret-pass");
        assertThat(secrets).contains("jtac.mail.smtp.password=s3cret-pass");
        assertThat(FilePermissions.isOwnerOnly(CONFIG.resolve("secrets.properties"))).isTrue();

        // Keeping the password: omit it.
        admin.client().put("/api/v1/system/mail", body(false, "smtp.example.com", 587, "STARTTLS", true, "", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mail.settings.enabled").value(false))
                .andExpect(jsonPath("$.mail.passwordSet").value(true));

        // Email pending: nothing is delivered and the sign-in screens are told.
        assertThat(dispatcher.dispatchDue()).isZero();
        admin.client().get("/api/v1/config").andExpect(jsonPath("$.emailEnabled").value(false));
    }
}
