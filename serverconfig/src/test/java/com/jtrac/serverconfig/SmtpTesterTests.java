package com.jtrac.serverconfig;

import static org.assertj.core.api.Assertions.assertThat;

import com.jtrac.serverconfig.testing.TestSmtpServer;
import com.jtrac.serverconfig.testing.TestSmtpServer.Mode;
import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;

/** Test emails against the controlled {@link TestSmtpServer}; nothing leaves this machine. */
class SmtpTesterTests {

    private static final String USER = "jtrac@example.test";
    private static final String PASSWORD = "correct-horse";

    private static SmtpSettings settings(TestSmtpServer server, TlsMode tls, boolean trustServer) {
        return new SmtpSettings(true, "localhost", server.port(), true, USER, tls, "jtrac@example.test", "JTRAC",
                "support@example.test", 5, 3, trustServer ? server.certificatePem().toString() : "");
    }

    private static SmtpTestResult.Step step(SmtpTestResult result, String prefix) {
        return result.steps().stream().filter(s -> s.name().startsWith(prefix)).findFirst().orElseThrow(
                () -> new AssertionError("No step " + prefix + " in " + result.steps()));
    }

    @Test
    void sendsOverStartTlsWithAuthentication() {
        try (TestSmtpServer server = TestSmtpServer.builder().mode(Mode.STARTTLS).start()) {
            SmtpTestResult result = SmtpTester.test(settings(server, TlsMode.STARTTLS, true), PASSWORD, "ops@example.test");

            assertThat(result.accepted()).as(result.toString()).isTrue();
            assertThat(step(result, "Start TLS").status()).isEqualTo(SmtpTestResult.Status.OK);
            assertThat(step(result, "Sign in").status()).isEqualTo(SmtpTestResult.Status.OK);
            assertThat(server.received()).hasSize(1);
            TestSmtpServer.Received message = server.received().getFirst();
            assertThat(message.overTls()).isTrue();
            assertThat(message.user()).isEqualTo(USER);
            assertThat(message.to()).containsExactly("ops@example.test");
            assertThat(message.data()).contains("Subject: JTRAC test email").contains("Reply-To: support@example.test");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void sendsOverImplicitTls() throws IOException {
        try (TestSmtpServer server = TestSmtpServer.builder().mode(Mode.IMPLICIT).start()) {
            SmtpTestResult result = SmtpTester.test(settings(server, TlsMode.IMPLICIT, true), PASSWORD, "ops@example.test");

            assertThat(result.accepted()).as(result.toString()).isTrue();
            assertThat(step(result, "TLS").status()).isEqualTo(SmtpTestResult.Status.OK);
            assertThat(server.received().getFirst().overTls()).isTrue();
        }
    }

    @Test
    void reportsWrongCredentialsWithoutLeakingThePassword() throws IOException {
        String wrong = "wrong-password-123";
        try (TestSmtpServer server = TestSmtpServer.builder().authFailureText("bad credentials: {password}").start()) {
            SmtpTestResult result = SmtpTester.test(settings(server, TlsMode.STARTTLS, true), wrong, "ops@example.test");

            assertThat(result.accepted()).isFalse();
            assertThat(step(result, "Sign in").status()).isEqualTo(SmtpTestResult.Status.FAILED);
            assertThat(step(result, "Sign in").detail()).contains("rejected the user name or password");
            assertThat(result.toString()).doesNotContain(wrong);
            assertThat(server.received()).isEmpty();
        }
    }

    @Test
    void neverTrustsAnUnknownCertificate() throws IOException {
        try (TestSmtpServer server = TestSmtpServer.builder().start()) {
            SmtpTestResult result = SmtpTester.test(settings(server, TlsMode.STARTTLS, false), PASSWORD, "ops@example.test");

            assertThat(result.accepted()).isFalse();
            assertThat(step(result, "Verify certificate").detail()).contains("not trusted").contains("never switched off");
            assertThat(server.received()).isEmpty();
        }
    }

    @Test
    void explainsWhenTheServerDoesNotOfferStartTls() throws IOException {
        try (TestSmtpServer server = TestSmtpServer.builder().mode(Mode.PLAIN).start()) {
            SmtpTestResult result = SmtpTester.test(settings(server, TlsMode.STARTTLS, true), PASSWORD, "ops@example.test");

            assertThat(result.accepted()).isFalse();
            assertThat(step(result, "Start TLS").detail()).contains("doesn't offer STARTTLS");
            assertThat(server.received()).isEmpty();
        }
    }

    @Test
    void explainsImplicitTlsAgainstAStartTlsPort() throws IOException {
        try (TestSmtpServer server = TestSmtpServer.builder().mode(Mode.STARTTLS).start()) {
            SmtpTestResult result = SmtpTester.test(settings(server, TlsMode.IMPLICIT, true), PASSWORD, "ops@example.test");

            assertThat(result.accepted()).isFalse();
            assertThat(result.steps()).anySatisfy(s -> assertThat(s.name()).startsWith("TLS handshake"));
        }
    }

    @Test
    void reportsARejectedSender() throws IOException {
        try (TestSmtpServer server = TestSmtpServer.builder().rejectSenderContaining("jtrac@").start()) {
            SmtpTestResult result = SmtpTester.test(settings(server, TlsMode.STARTTLS, true), PASSWORD, "ops@example.test");

            assertThat(result.accepted()).isFalse();
            assertThat(step(result, "Send test message").detail()).contains("553").contains("signed-in mailbox");
        }
    }

    @Test
    void reportsThatTheRelayRequiresAuthentication() throws IOException {
        try (TestSmtpServer server = TestSmtpServer.builder().mode(Mode.STARTTLS).start()) {
            SmtpSettings noAuth = new SmtpSettings(true, "localhost", server.port(), false, "", TlsMode.STARTTLS,
                    "jtrac@example.test", "JTRAC", "", 5, 3, server.certificatePem().toString());
            SmtpTestResult result = SmtpTester.test(noAuth, "", "ops@example.test");

            assertThat(result.accepted()).isFalse();
            assertThat(step(result, "Send test message").detail()).contains("530").contains("sign in");
        }
    }

    @Test
    void reportsARefusedConnection() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        SmtpSettings smtp = new SmtpSettings(true, "127.0.0.1", closedPort, true, USER, TlsMode.STARTTLS, "jtrac@example.test",
                "JTRAC", "", 3, 3, "");
        SmtpTestResult result = SmtpTester.test(smtp, PASSWORD, "ops@example.test");

        assertThat(result.accepted()).isFalse();
        assertThat(step(result, "Connect").detail()).contains("refused");
    }

    @Test
    void reportsAnUnknownHost() {
        SmtpSettings smtp = new SmtpSettings(true, "smtp.does-not-exist.invalid", 587, true, USER, TlsMode.STARTTLS,
                "jtrac@example.test", "JTRAC", "", 5, 3, "");
        SmtpTestResult result = SmtpTester.test(smtp, PASSWORD, "ops@example.test");

        assertThat(result.accepted()).isFalse();
        assertThat(step(result, "Resolve").status()).isEqualTo(SmtpTestResult.Status.FAILED);
    }

    @Test
    void refusesToSendPasswordsUnencrypted() {
        SmtpSettings smtp = new SmtpSettings(true, "localhost", 25, true, USER, TlsMode.NONE, "jtrac@example.test", "JTRAC", "",
                5, 3, "");
        SmtpTestResult result = SmtpTester.test(smtp, PASSWORD, "ops@example.test");

        assertThat(result.accepted()).isFalse();
        assertThat(step(result, "Check settings").detail()).contains("unencrypted");
    }

    @Test
    void sendsThroughATrustedRelayWithoutAuthentication() throws IOException {
        try (TestSmtpServer server = TestSmtpServer.builder().mode(Mode.PLAIN).credentials(null, null).start()) {
            SmtpSettings relay = new SmtpSettings(true, "localhost", server.port(), false, "", TlsMode.NONE,
                    "jtrac@example.test", "", "", 5, 3, "");
            SmtpTestResult result = SmtpTester.test(relay, "", "ops@example.test");

            assertThat(result.accepted()).as(result.toString()).isTrue();
            assertThat(result.steps()).anySatisfy(s -> assertThat(s.status()).isEqualTo(SmtpTestResult.Status.WARNING));
            assertThat(server.received().getFirst().overTls()).isFalse();
        }
    }
}
