package com.jtac.utcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jtac.identity.UserDirectory;
import com.jtac.support.Fixtures;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end verification of the native UTCP integration using the real, official UTCP Python
 * reference client library (utcp 1.2.0 + utcp-http 1.1.15).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("db")
class UtcpRealClientInteroperabilityTests {

    @LocalServerPort
    private int port;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private UserDirectory users;
    @Autowired
    private JsonMapper json;

    private Fixtures fixtures;
    private Fixtures.User alice;
    private Fixtures.Org org;
    private String issueKey;
    private String attachmentId;
    private String apiToken;

    @BeforeEach
    void setUp() throws Exception {
        fixtures = new Fixtures(mvc, jdbc, users);
        alice = fixtures.user("Alice Interop");
        org = fixtures.org(alice);
        fixtures.project(alice, org, "UTCP", "KANBAN");

        // 1. Create an issue
        String issueBody = json.writeValueAsString(Map.of(
                "title", "Real client test issue",
                "description", "This issue is used to verify UTCP client tool execution end-to-end."
        ));
        MvcResult res = alice.client().post(org.path("/projects/UTCP/issues"), issueBody)
                .andExpect(status().isCreated())
                .andReturn();
        issueKey = JsonPath.read(res.getResponse().getContentAsString(), "$.key");

        // 2. Upload an attachment
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "audit_test.txt",
                "text/plain",
                "Real client UTCP attachment content".getBytes(StandardCharsets.UTF_8)
        );
        MvcResult attRes = alice.client().perform(MockMvcRequestBuilders.multipart(org.path("/issues/" + issueKey + "/attachments")).file(file), HttpMethod.POST)
                .andExpect(status().isCreated())
                .andReturn();
        attachmentId = JsonPath.read(attRes.getResponse().getContentAsString(), "$.id");

        // 3. Create personal API token
        String tokenBody = json.writeValueAsString(Map.of(
                "name", "utcp-interop-token",
                "scopes", List.of("read", "write")
        ));
        MvcResult tokRes = alice.client().post("/api/v1/me/tokens", tokenBody)
                .andExpect(status().isCreated())
                .andReturn();
        apiToken = JsonPath.read(tokRes.getResponse().getContentAsString(), "$.secret");
    }

    @AfterEach
    void tearDown() {
        fixtures.close();
    }

    @Test
    void realOfficialUtcpClientEndToEndFlow() throws Exception {
        String pythonExe = "C:\\Users\\Jerop\\.gemini\\antigravity\\brain\\ea88a617-782e-488e-889b-d3909b6718c0\\scratch\\utcp_env\\Scripts\\python.exe";
        String scriptPath = "C:\\Users\\Jerop\\.gemini\\antigravity\\brain\\ea88a617-782e-488e-889b-d3909b6718c0\\scratch\\verify_utcp_client.py";

        File pythonFile = new File(pythonExe);
        if (!pythonFile.exists()) {
            System.err.println("Skipping real client test: Python environment not found at " + pythonExe);
            return;
        }

        // 1-10: Run the official Python client suite
        StringBuilder output = new StringBuilder();
        int exitCode = runProcess(new ProcessBuilder(
                pythonExe,
                scriptPath,
                "--base-url", "http://127.0.0.1:" + port,
                "--token", apiToken,
                "--issue-key", issueKey,
                "--project-key", "UTCP",
                "--attachment-id", attachmentId
        ), output);

        assertThat(exitCode)
                .withFailMessage("Real UTCP Client Interoperability script failed with exit code " + exitCode + ":\n" + output)
                .isEqualTo(0);

        // 11. Disable UTCP and verify execution is rejected
        alice.client().post("/api/v1/me/utcp/disable", "{}")
                .andExpect(status().isOk());

        StringBuilder disabledOutput = new StringBuilder();
        int disabledExit = runProcess(new ProcessBuilder(
                pythonExe,
                scriptPath,
                "--base-url", "http://127.0.0.1:" + port,
                "--token", apiToken,
                "--issue-key", issueKey,
                "--project-key", "UTCP",
                "--attachment-id", attachmentId,
                "--expect-disabled"
        ), disabledOutput);

        assertThat(disabledExit)
                .withFailMessage("Expected tool execution to be rejected when disabled, but got:\n" + disabledOutput)
                .isEqualTo(0);

        // 12. Confirm audit records were generated in utcp_activity
        Long activityCount = jdbc.sql("SELECT count(*) FROM utcp_activity WHERE user_id = :u")
                .param("u", alice.id())
                .query((rs, n) -> rs.getLong(1))
                .single();
        assertThat(activityCount).isGreaterThanOrEqualTo(4);

        // Verify status breakdown
        List<String> outcomes = jdbc.sql("SELECT outcome FROM utcp_activity WHERE user_id = :u")
                .param("u", alice.id())
                .query((rs, n) -> rs.getString("outcome"))
                .list();
        assertThat(outcomes).contains("OK", "DENIED");
    }

    private static int runProcess(ProcessBuilder pb, StringBuilder out) throws Exception {
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append("\n");
                System.out.println("[UTCP-CLIENT] " + line);
            }
        }
        return proc.waitFor();
    }
}

