package com.jtrac.serverconfig;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigStoreTests {

    @TempDir
    Path dir;

    static ServerConfig sample(Path base) {
        return new ServerConfig(
                new ServerConfig.Http("127.0.0.1", 8080, "https://jtrac.example.com", true),
                new ServerConfig.Database(DatabaseMode.MANAGED, "127.0.0.1", 54329, "jtrac", "jtrac_app", "jtrac_owner", "disable"),
                new SmtpSettings(true, "smtp.example.com", 587, true, "jtrac@example.com", TlsMode.STARTTLS, "jtrac@example.com",
                        "JTRAC Café", "", 15, 8, ""),
                new ServerConfig.Security(ServerConfig.RegistrationMode.INVITE_ONLY, 8),
                new ServerConfig.Storage(base.resolve("attachments").toString(), base.resolve("backups").toString(),
                        base.resolve("logs").toString()),
                new ServerConfig.Legal("Example Pvt Ltd", "1 Main Road, Chennai", "privacy@example.com", "A. Officer",
                        "grievance@example.com", "Chennai, Tamil Nadu"));
    }

    @Test
    void roundTripsSettingsAndKeepsUnknownKeys() throws IOException {
        ConfigStore store = new ConfigStore(dir);
        Files.writeString(store.settingsFile(), "custom.operator.key=kept\n", StandardCharsets.ISO_8859_1);

        ServerConfig config = sample(dir);
        store.saveConfig(config);

        assertThat(store.loadConfig()).isEqualTo(config);
        assertThat(store.loadRawSettings()).containsEntry("custom.operator.key", "kept")
                .containsEntry("server.servlet.session.cookie.secure", "true")
                .containsEntry("spring.datasource.url", "jdbc:postgresql://127.0.0.1:54329/jtrac?sslmode=disable")
                .containsEntry("jtac.mail.transport", "smtp");
    }

    @Test
    void keepsSecretsInSeparateOwnerOnlyFiles() throws IOException {
        ConfigStore store = new ConfigStore(dir);
        store.saveConfig(sample(dir));
        store.saveSecrets(new Secrets("db-pass-1", "smtp-pass-2", "a2V5"));
        store.saveAdminSecrets(new AdminSecrets("owner-pass-3", "super-pass-4"));

        String settings = Files.readString(store.settingsFile(), StandardCharsets.ISO_8859_1);
        assertThat(settings).doesNotContain("db-pass-1", "smtp-pass-2", "owner-pass-3", "super-pass-4");
        assertThat(Files.readString(store.secretsFile(), StandardCharsets.ISO_8859_1)).doesNotContain("owner-pass-3");
        assertThat(store.loadSecrets()).isEqualTo(new Secrets("db-pass-1", "smtp-pass-2", "a2V5"));
        assertThat(store.loadAdminSecrets()).isEqualTo(new AdminSecrets("owner-pass-3", "super-pass-4"));
        assertThat(FilePermissions.isOwnerOnly(store.secretsFile())).isTrue();
        assertThat(FilePermissions.isOwnerOnly(store.adminSecretsFile())).isTrue();
        assertThat(store.loadSecrets().toString()).doesNotContain("db-pass-1");
    }

    @Test
    void escapesSpecialCharacters() throws IOException {
        ConfigStore store = new ConfigStore(dir);
        store.saveSecrets(new Secrets("p=ss:wo#rd\\ with spaces", "naïve€", "k"));

        assertThat(store.loadSecrets().databasePassword()).isEqualTo("p=ss:wo#rd\\ with spaces");
        assertThat(store.loadSecrets().smtpPassword()).isEqualTo("naïve€");
    }

    @Test
    void validatesASampleConfiguration() {
        List<Problem> problems = ConfigValidator.validate(sample(dir), new Secrets("db", "smtp", "key"));
        assertThat(ConfigValidator.hasErrors(problems)).as(problems.toString()).isFalse();
    }

    @Test
    void rejectsInvalidValues() {
        ServerConfig bad = sample(dir)
                .withHttp(new ServerConfig.Http("not a host!", 70000, "ftp://x", false))
                .withSmtp(new SmtpSettings(true, "", 0, true, "", TlsMode.NONE, "not-an-email", "", "", 0, 0, ""));
        List<Problem> problems = ConfigValidator.validate(bad, new Secrets("", "", ""));

        assertThat(problems).extracting(Problem::field).contains("http.bindAddress", "http.port", "http.publicUrl",
                "smtp.host", "smtp.port", "smtp.username", "smtp.password", "smtp.tls", "smtp.fromAddress",
                "smtp.timeoutSeconds", "database.appPassword", "security.integrationSecretKey");
    }

    @Test
    void detectsAPortInUse() throws IOException {
        try (ServerSocket taken = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            assertThat(PortCheck.problem("127.0.0.1", taken.getLocalPort())).isPresent();
        }
        assertThat(PortCheck.problem("127.0.0.1", PortCheck.firstFree("127.0.0.1", 18080))).isEmpty();
    }

    @Test
    void aPortWithOnlyTimeWaitConnectionsIsFree() throws IOException {
        int port;
        try (ServerSocket listener = new ServerSocket()) {
            listener.setReuseAddress(true);
            listener.bind(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0));
            port = listener.getLocalPort();
            try (java.net.Socket client = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), port)) {
                // Closing the accepted side first leaves its connection in TIME_WAIT on the listening port.
                listener.accept().close();
                client.getInputStream().read();
            }
        }
        assertThat(PortCheck.problem("127.0.0.1", port)).isEmpty();
    }

    @Test
    void redactsSecretValues() {
        assertThat(Redaction.redact(java.util.Map.of("jtac.mail.smtp.password", "x", "server.port", "8080")))
                .containsEntry("jtac.mail.smtp.password", "****").containsEntry("server.port", "8080");
        assertThat(Redaction.scrub("535 bad creds for hunter22", "hunter22")).isEqualTo("535 bad creds for ****");
    }
}
