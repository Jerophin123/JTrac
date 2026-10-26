package com.jtac.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.jayway.jsonpath.JsonPath;
import com.jtac.DatabaseIntegrationTest;
import com.jtac.identity.UserDirectory;
import com.jtac.mail.internal.MailDispatcher;
import com.jtac.support.Fixtures;
import com.jtac.support.TestClient;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Comments, mentions, notifications and attachments, plus the email flows (password reset,
 * assignment notification) delivered over SMTP to an in-process GreenMail server.
 */
@DatabaseIntegrationTest
class CollaborationAndMailTests {

    @RegisterExtension
    static final GreenMailExtension SMTP = new GreenMailExtension(ServerSetupTest.SMTP);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private UserDirectory users;

    @Autowired
    private MailDispatcher mail;

    private Fixtures fx;
    private Fixtures.User owner;
    private Fixtures.User dev;
    private Fixtures.Org org;

    @BeforeEach
    void setUp() throws Exception {
        fx = new Fixtures(mvc, jdbc, users);
        owner = fx.user("Owner");
        dev = fx.user("Dev");
        org = fx.org(owner);
        fx.member(org, dev, "MEMBER");
        fx.project(owner, org, "COL", "KANBAN");
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    @Test
    void assignmentAndMentionsNotifyInAppAndByEmail() throws Exception {
        String key = fx.issue(owner, org, "COL", "{\"title\":\"Fix login\",\"assigneeId\":\"" + dev.id() + "\"}");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                dev.client().get(org.path("/notifications/unread-count")).andExpect(jsonPath("$.count").value(1)));
        dev.client().get(org.path("/notifications")).andExpect(jsonPath("$.items[0].kind").value("ASSIGNED"))
                .andExpect(jsonPath("$.items[0].link").value("/" + org.slug() + "/issue/" + key));
        // The actor is never notified about their own change.
        owner.client().get(org.path("/notifications/unread-count")).andExpect(jsonPath("$.count").value(0));

        mail.dispatchDue();
        MimeMessage assigned = waitForMail(dev.email(), "Fix login");
        assertThat(text(assigned)).contains("/" + org.slug() + "/issue/" + key);

        String comment = owner.client().post(org.path("/issues/" + key + "/comments"),
                        "{\"body\":\"@" + dev.handle() + " can you check <script>alert(1)</script>?\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(comment, "$.body")).contains("<script>"); // stored as Markdown source, rendered safely by the client
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                dev.client().get(org.path("/notifications?unread=true")).andExpect(jsonPath("$.items[0].kind").value("MENTIONED")));

        // Preferences: turning off in-app notifications for comments silences them.
        dev.client().put(org.path("/notifications/preferences"),
                "[{\"kind\":\"COMMENTED\",\"inApp\":false,\"email\":false}]").andExpect(status().isOk());
        dev.client().post(org.path("/notifications/read-all"), null).andExpect(jsonPath("$.updated").value(2));
        owner.client().post(org.path("/issues/" + key + "/comments"), "{\"body\":\"plain update\"}").andExpect(status().isCreated());
        Thread.sleep(500);
        dev.client().get(org.path("/notifications/unread-count")).andExpect(jsonPath("$.count").value(0));

        // Only the author may edit; others need DELETE_ANY_COMMENT to delete.
        String commentId = JsonPath.read(comment, "$.id");
        dev.client().patch(org.path("/issues/" + key + "/comments/" + commentId), "{\"body\":\"hijack\",\"version\":0}")
                .andExpect(status().isForbidden());
        dev.client().delete(org.path("/issues/" + key + "/comments/" + commentId)).andExpect(status().isForbidden());
        owner.client().patch(org.path("/issues/" + key + "/comments/" + commentId), "{\"body\":\"edited\",\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.editedAt").isNotEmpty());
    }

    @Test
    void passwordResetByEmailEndsOtherSessions() throws Exception {
        TestClient anonymous = new TestClient(mvc);
        anonymous.post("/api/v1/auth/password-reset", "{\"email\":\"" + dev.email() + "\"}").andExpect(status().isAccepted());
        anonymous.post("/api/v1/auth/password-reset", "{\"email\":\"nobody-" + dev.email() + "\"}").andExpect(status().isAccepted());
        mail.dispatchDue();
        MimeMessage message = waitForMail(dev.email(), "Reset your JTRAC password");
        Matcher token = Pattern.compile("reset-password\\?token=([A-Za-z0-9_-]+)").matcher(text(message));
        assertThat(token.find()).isTrue();

        anonymous.post("/api/v1/auth/password-reset/confirm", "{\"token\":\"" + token.group(1) + "\",\"password\":\"short\"}")
                .andExpect(status().isBadRequest());
        String newPassword = "New-" + java.util.UUID.randomUUID();
        anonymous.post("/api/v1/auth/password-reset/confirm", "{\"token\":\"" + token.group(1) + "\",\"password\":\""
                + newPassword + "\"}").andExpect(status().isNoContent());
        // Tokens are single-use.
        anonymous.post("/api/v1/auth/password-reset/confirm", "{\"token\":\"" + token.group(1) + "\",\"password\":\""
                + newPassword + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:jtac:problem:invalid-token"));
        dev.client().get("/api/v1/auth/session").andExpect(status().isUnauthorized());
        new TestClient(mvc).login(dev.email(), dev.password()).andExpect(status().isUnauthorized());
        new TestClient(mvc).login(dev.email(), newPassword).andExpect(status().isOk());
    }

    @Test
    void attachmentsAreAuthorizedAndNeverServedAsActiveContent() throws Exception {
        String key = fx.issue(owner, org, "COL", "{\"title\":\"With files\"}");
        MockMultipartFile html = new MockMultipartFile("file", "page.html", "text/html", "<script>alert(1)</script>".getBytes());
        String uploaded = owner.client().perform(MockMvcRequestBuilders.multipart(org.path("/issues/" + key + "/attachments"))
                        .file(html), HttpMethod.POST)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(uploaded, "$.id");
        dev.client().get(org.path("/issues/" + key + "/attachments/" + id + "/content?inline=true"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/octet-stream"))
                .andExpect(header().string("Content-Disposition", startsWith("attachment")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        Fixtures.User outsider = fx.user("Outsider");
        outsider.client().get(org.path("/issues/" + key + "/attachments/" + id + "/content")).andExpect(status().isNotFound());
        // Members without MANAGE_ATTACHMENTS cannot delete someone else's file.
        dev.client().delete(org.path("/issues/" + key + "/attachments/" + id)).andExpect(status().isForbidden());
        owner.client().delete(org.path("/issues/" + key + "/attachments/" + id)).andExpect(status().isNoContent());
        owner.client().get(org.path("/issues/" + key + "/attachments")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void apiTokensAuthenticateWithoutCsrfAndRespectScopes() throws Exception {
        String readOnly = JsonPath.read(owner.client().post("/api/v1/me/tokens", "{\"name\":\"ro\",\"scopes\":[\"read\"]}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.secret");
        String readWrite = JsonPath.read(owner.client().post("/api/v1/me/tokens", "{\"name\":\"rw\",\"scopes\":[\"read\",\"write\"]}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.secret");
        assertThat(readOnly).startsWith("jtrac_");

        TestClient ro = TestClient.withToken(mvc, readOnly);
        ro.get(org.path("/projects")).andExpect(status().isOk());
        ro.post(org.path("/projects/COL/issues"), "{\"title\":\"x\"}").andExpect(status().isForbidden());
        TestClient rw = TestClient.withToken(mvc, readWrite);
        rw.post(org.path("/projects/COL/issues"), "{\"title\":\"via token\"}").andExpect(status().isCreated());
        TestClient.withToken(mvc, readWrite + "x").get(org.path("/projects")).andExpect(status().isUnauthorized());

        String tokens = owner.client().get("/api/v1/me/tokens").andReturn().getResponse().getContentAsString();
        assertThat(tokens).doesNotContain(readOnly).doesNotContain(readWrite);
        String rwId = JsonPath.<java.util.List<String>>read(tokens, "$[?(@.name=='rw')].id").getFirst();
        owner.client().delete("/api/v1/me/tokens/" + rwId).andExpect(status().isNoContent());
        rw.get(org.path("/projects")).andExpect(status().isUnauthorized());
    }

    /** The decoded text of all parts (bodies are quoted-printable encoded on the wire). */
    private static String text(MimeMessage message) throws Exception {
        StringBuilder out = new StringBuilder();
        collect(message.getContent(), out);
        return out.toString();
    }

    private static void collect(Object content, StringBuilder out) throws Exception {
        if (content instanceof jakarta.mail.Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                collect(multipart.getBodyPart(i).getContent(), out);
            }
        } else {
            out.append(content).append(System.lineSeparator());
        }
    }

    private static MimeMessage waitForMail(String to, String subjectPart) {
        return await().atMost(Duration.ofSeconds(10)).until(() -> Arrays.stream(SMTP.getReceivedMessages())
                .filter(m -> {
                    try {
                        return Arrays.stream(m.getAllRecipients()).anyMatch(a -> a.toString().contains(to))
                                && m.getSubject().contains(subjectPart);
                    } catch (jakarta.mail.MessagingException e) {
                        return false;
                    }
                }).findFirst().orElse(null), m -> m != null);
    }
}
