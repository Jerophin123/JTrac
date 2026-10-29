package com.jtac.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jtac.support.Fixtures;
import java.net.URI;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plays an MCP client (like Claude Code or Codex) against the real endpoints: dynamic
 * registration, the authorization request, the owner's approval in the web app, the PKCE
 * code exchange, refresh, and JSON-RPC calls to the connection's MCP URL.
 */
final class AgentHarness {

    static final String REDIRECT = "http://127.0.0.1:43123/callback";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MockMvc mvc;
    private final JsonMapper json;
    private final JdbcClient jdbc;
    private final List<String> clients = new ArrayList<>();

    AgentHarness(MockMvc mvc, JsonMapper json, JdbcClient jdbc) {
        this.mvc = mvc;
        this.json = json;
        this.jdbc = jdbc;
    }

    record Connection(String id, String url, String publicId, long version) {
    }

    record Tokens(String access, String refresh, String clientId, String scope) {
    }

    Connection createConnection(Fixtures.User owner, Fixtures.Org org, List<String> scopes) throws Exception {
        String body = json.writeValueAsString(Map.of("name", "Test agent", "organizationId", org.id().toString(),
                "scopes", scopes));
        String response = owner.client().post("/api/v1/me/agent-connections", body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String url = JsonPath.read(response, "$.url");
        return new Connection(JsonPath.read(response, "$.id"), url, url.substring(url.lastIndexOf('/') + 1),
                ((Number) JsonPath.read(response, "$.version")).longValue());
    }

    String register(String redirectUri) throws Exception {
        String response = mvc.perform(MockMvcRequestBuilders.post("/oauth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("client_name", "Harness agent", "redirect_uris", List.of(redirectUri),
                                "token_endpoint_auth_method", "none", "grant_types", List.of("authorization_code", "refresh_token")))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String clientId = JsonPath.read(response, "$.client_id");
        clients.add(clientId);
        return clientId;
    }

    static String verifier() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    MvcResult authorize(Map<String, String> params) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.get("/oauth/authorize");
        params.forEach(request::param);
        return mvc.perform(request).andReturn();
    }

    Map<String, String> authorizeParams(String clientId, String verifier, String resource, String scope) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", clientId);
        params.put("redirect_uri", REDIRECT);
        params.put("code_challenge", OAuthService.s256(verifier));
        params.put("code_challenge_method", "S256");
        params.put("state", "st-" + verifier.substring(0, 8));
        params.put("resource", resource);
        if (scope != null) {
            params.put("scope", scope);
        }
        return params;
    }

    static Map<String, String> query(String url) {
        Map<String, String> result = new LinkedHashMap<>();
        UriComponentsBuilder.fromUri(URI.create(url)).build().getQueryParams()
                .forEach((k, v) -> result.put(k, v.isEmpty() ? null : java.net.URLDecoder.decode(v.getFirst(),
                        java.nio.charset.StandardCharsets.UTF_8)));
        return result;
    }

    /** The consent-screen handle from the authorize redirect. */
    static String handle(MvcResult authorize) {
        assertThat(authorize.getResponse().getStatus()).isEqualTo(302);
        String location = authorize.getResponse().getHeader("Location");
        assertThat(location).contains("/connect-agent?request=");
        return query(location).get("request");
    }

    /** Approves as {@code owner}; returns the code. */
    String approve(Fixtures.User owner, String handle) throws Exception {
        String response = owner.client().post("/api/v1/me/agent-authorizations/" + handle + "/approve", "{}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String redirect = JsonPath.read(response, "$.redirectUrl");
        assertThat(redirect).startsWith(REDIRECT);
        return query(redirect).get("code");
    }

    ResultActions token(Map<String, String> form) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post("/oauth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED);
        form.forEach(request::param);
        return mvc.perform(request);
    }

    /** The full sign-in an agent performs; returns its tokens. */
    Tokens signIn(Fixtures.User owner, Connection connection, String scope) throws Exception {
        String clientId = register(REDIRECT);
        String verifier = verifier();
        String code = approve(owner, handle(authorize(authorizeParams(clientId, verifier, connection.url(), scope))));
        String response = token(Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", REDIRECT,
                "client_id", clientId, "code_verifier", verifier, "resource", connection.url()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Tokens(JsonPath.read(response, "$.access_token"), JsonPath.read(response, "$.refresh_token"), clientId,
                JsonPath.read(response, "$.scope"));
    }

    MvcResult mcp(String publicId, String accessToken, String body) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post("/mcp/" + publicId)
                .contentType(MediaType.APPLICATION_JSON).accept("application/json, text/event-stream").content(body);
        if (accessToken != null) {
            request.header("Authorization", "Bearer " + accessToken);
        }
        return mvc.perform(request).andReturn();
    }

    /** {@code tools/call}; returns the JSON-RPC result ({@code content}, {@code structuredContent}, {@code isError}). */
    Map<String, Object> call(Connection connection, Tokens tokens, String tool, Map<String, Object> arguments) throws Exception {
        String body = json.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", tool, "arguments", arguments)));
        MvcResult result = mcp(connection.publicId(), tokens.access(), body);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        String text = result.getResponse().getContentAsString();
        Map<String, Object> response = JsonPath.read(text, "$");
        assertThat(response).as(text).containsKey("result");
        return JsonPath.read(text, "$.result");
    }

    static boolean isError(Map<String, Object> result) {
        return Boolean.TRUE.equals(result.get("isError"));
    }

    static String text(Map<String, Object> result) {
        return JsonPath.read(result, "$.content[0].text");
    }

    void close() {
        for (String client : clients) {
            jdbc.sql("DELETE FROM mcp_oauth_client WHERE client_id = :c").param("c", client).update();
        }
    }
}
