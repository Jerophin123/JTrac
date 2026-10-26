package com.jtac.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jtac.DatabaseIntegrationTest;
import com.jtac.identity.UserDirectory;
import com.jtac.integration.internal.WebhookDispatcher;
import com.jtac.support.Fixtures;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Webhooks against a real local HTTP receiver, and CSV export/import. */
@DatabaseIntegrationTest
class IntegrationApiTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private UserDirectory users;

    @Autowired
    private WebhookDispatcher dispatcher;

    private Fixtures fx;
    private Fixtures.User owner;
    private Fixtures.Org org;
    private HttpServer receiver;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger failuresToSimulate = new AtomicInteger();

    record Received(Map<String, String> headers, String body) {
    }

    @BeforeEach
    void setUp() throws Exception {
        fx = new Fixtures(mvc, jdbc, users);
        owner = fx.user("Owner");
        org = fx.org(owner);
        fx.project(owner, org, "HOOK", "KANBAN");
        receiver = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        receiver.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(Map.of(
                    "event", String.valueOf(exchange.getRequestHeaders().getFirst("X-JTRAC-Event")),
                    "timestamp", String.valueOf(exchange.getRequestHeaders().getFirst("X-JTRAC-Timestamp")),
                    "signature", String.valueOf(exchange.getRequestHeaders().getFirst("X-JTRAC-Signature"))), body));
            int code = failuresToSimulate.getAndUpdate(n -> Math.max(0, n - 1)) > 0 ? 503 : 204;
            exchange.sendResponseHeaders(code, -1);
            exchange.close();
        });
        receiver.start();
    }

    @AfterEach
    void tearDown() {
        receiver.stop(0);
        fx.close();
    }

    private String hookUrl() {
        return "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook";
    }

    @Test
    void issueEventsAreDeliveredSignedAndRetried() throws Exception {
        String created = owner.client().post(org.path("/webhooks"), "{\"url\":\"" + hookUrl()
                        + "\",\"events\":[\"issue.created\",\"issue.updated\"],\"projectKey\":\"HOOK\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String secret = JsonPath.read(created, "$.secret");
        String webhookId = JsonPath.read(created, "$.webhook.id");
        assertThat(secret).startsWith("whsec_");
        owner.client().get(org.path("/webhooks/" + webhookId)).andExpect(jsonPath("$.secret").doesNotExist());

        failuresToSimulate.set(1);
        String key = fx.issue(owner, org, "HOOK", "{\"title\":\"Hooked\",\"description\":\"body text\"}");
        // The listener queues the delivery after commit (asynchronously).
        await().atMost(Duration.ofSeconds(10)).until(() -> pending(webhookId) == 1);

        assertThat(dispatcher.dispatchDue()).isGreaterThanOrEqualTo(1);
        assertThat(received).hasSize(1);
        owner.client().get(org.path("/webhooks/" + webhookId + "/deliveries"))
                .andExpect(jsonPath("$.items[0].status").value("PENDING"))
                .andExpect(jsonPath("$.items[0].attempts").value(1))
                .andExpect(jsonPath("$.items[0].lastStatusCode").value(503));

        // Make the retry due now instead of after the backoff.
        jdbc.sql("UPDATE webhook_delivery SET next_attempt_at = now() WHERE webhook_id = :id")
                .param("id", java.util.UUID.fromString(webhookId)).update();
        dispatcher.dispatchDue();
        assertThat(received).hasSize(2);
        owner.client().get(org.path("/webhooks/" + webhookId + "/deliveries"))
                .andExpect(jsonPath("$.items[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.items[0].attempts").value(2));

        Received delivery = received.get(1);
        assertThat(delivery.headers().get("event")).isEqualTo("issue.created");
        assertThat(delivery.headers().get("signature"))
                .isEqualTo("sha256=" + hmac(secret, delivery.headers().get("timestamp") + "." + delivery.body()));
        assertThat(JsonPath.<String>read(delivery.body(), "$.issue.key")).isEqualTo(key);
        assertThat(JsonPath.<String>read(delivery.body(), "$.type")).isEqualTo("issue.created");
        assertThat(delivery.body()).as("payloads are minimal").doesNotContain("body text");

        // Redelivery and ping.
        String deliveryId = JsonPath.read(owner.client().get(org.path("/webhooks/" + webhookId + "/deliveries"))
                .andReturn().getResponse().getContentAsString(), "$.items[0].id");
        owner.client().post(org.path("/webhooks/" + webhookId + "/deliveries/" + deliveryId + "/redeliver"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        owner.client().post(org.path("/webhooks/" + webhookId + "/ping"), null).andExpect(status().isAccepted());
        dispatcher.dispatchDue();
        assertThat(received).hasSize(4);
        assertThat(received.stream().map(r -> r.headers().get("event")).toList()).contains("ping");

        // A rotated secret signs subsequent deliveries.
        String rotated = JsonPath.read(owner.client().post(org.path("/webhooks/" + webhookId + "/rotate-secret"), null)
                .andReturn().getResponse().getContentAsString(), "$.secret");
        assertThat(rotated).isNotEqualTo(secret);
        owner.client().post(org.path("/webhooks/" + webhookId + "/ping"), null);
        dispatcher.dispatchDue();
        Received last = received.getLast();
        assertThat(last.headers().get("signature")).isEqualTo("sha256=" + hmac(rotated, last.headers().get("timestamp") + "." + last.body()));
    }

    @Test
    void onlyAdminsManageWebhooks() throws Exception {
        Fixtures.User member = fx.user("Member");
        fx.member(org, member, "MEMBER");
        member.client().get(org.path("/webhooks")).andExpect(status().isForbidden());
        member.client().post(org.path("/webhooks"), "{\"url\":\"" + hookUrl() + "\",\"events\":[\"issue.created\"]}")
                .andExpect(status().isForbidden());
        owner.client().post(org.path("/webhooks"), "{\"url\":\"" + hookUrl() + "\",\"events\":[\"nope\"]}")
                .andExpect(status().isBadRequest());
        owner.client().get(org.path("/audit?action=webhook")).andExpect(status().isOk());
    }

    @Test
    void csvExportNeutralizesFormulasAndImportIsAllOrNothing() throws Exception {
        fx.issue(owner, org, "HOOK", "{\"title\":\"=HYPERLINK(\\\"http://evil\\\")\"}");
        String csv = owner.client().post(org.path("/issues/export"), "{\"filter\":{\"projectKeys\":[\"HOOK\"]}}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿key,title,");
        assertThat(csv).contains("'=HYPERLINK").doesNotContain(",=HYPERLINK");

        owner.client().post(org.path("/projects/HOOK/labels"), "{\"name\":\"backend\"}").andExpect(status().isCreated());
        String bad = "title,priority,labels,dueDate,assignee\n"
                + "Valid row,HIGH,backend,2026-11-01,\n"
                + ",LOW,,,\n"
                + "Bad label,MEDIUM,nope,2026-13-01,nobody@nowhere.test\n";
        String result = importCsv(bad, false);
        assertThat(JsonPath.<List<Object>>read(result, "$.createdKeys")).isEmpty();
        assertThat(JsonPath.<List<String>>read(result, "$.errors[*].column")).contains("title", "labels", "dueDate", "assignee");
        owner.client().post(org.path("/issues/search"), "{\"filter\":{\"projectKeys\":[\"HOOK\"],\"text\":\"Valid row\"}}")
                .andExpect(jsonPath("$.total").value(0));

        String good = "Title,Priority,Labels,Assignee\nImported one,HIGH,backend," + owner.handle() + "\nImported two,,,\n";
        String dry = importCsv(good, true);
        assertThat(JsonPath.<List<String>>read(dry, "$.createdKeys")).hasSize(2);
        owner.client().post(org.path("/issues/search"), "{\"filter\":{\"projectKeys\":[\"HOOK\"],\"text\":\"Imported\"}}")
                .andExpect(jsonPath("$.total").value(0));
        String real = importCsv(good, false);
        assertThat(JsonPath.<List<String>>read(real, "$.createdKeys")).hasSize(2);
        owner.client().post(org.path("/issues/search"), "{\"filter\":{\"projectKeys\":[\"HOOK\"],\"text\":\"Imported\"}}")
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[*].priority").value(hasItem("HIGH")));
    }

    private String importCsv(String content, boolean dryRun) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "issues.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
        return owner.client().perform(MockMvcRequestBuilders.multipart(org.path("/projects/HOOK/import"))
                        .file(file).param("dryRun", String.valueOf(dryRun)), HttpMethod.POST)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private long pending(String webhookId) {
        return jdbc.sql("SELECT count(*) FROM webhook_delivery WHERE webhook_id = :id AND status = 'PENDING'")
                .param("id", java.util.UUID.fromString(webhookId)).query(Long.class).single();
    }

    private static String hmac(String secret, String content) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
    }
}
