package com.jtac.support;

import jakarta.servlet.http.Cookie;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * A browser-like API client for MockMvc: keeps cookies between requests (the JDBC-backed
 * session cookie and the XSRF-TOKEN cookie) and echoes the CSRF token on unsafe requests,
 * exactly as the SPA does. Use one instance per simulated user.
 */
public final class TestClient {

    public static final String SESSION_COOKIE = "JTRAC_SESSION";
    private static final Set<HttpMethod> SAFE = Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);

    private final MockMvc mvc;
    private final Map<String, Cookie> cookies = new LinkedHashMap<>();
    private String bearer;

    public TestClient(MockMvc mvc) {
        this.mvc = mvc;
    }

    /** A client that authenticates with an API token instead of cookies (no CSRF needed). */
    public static TestClient withToken(MockMvc mvc, String token) {
        TestClient client = new TestClient(mvc);
        client.bearer = token;
        return client;
    }

    public ResultActions login(String email, String password) throws Exception {
        return post("/api/v1/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    public ResultActions get(String path, Object... vars) throws Exception {
        return perform(MockMvcRequest.of(MockMvcRequestBuilders.get(path, vars)), HttpMethod.GET);
    }

    public ResultActions post(String path, String json, Object... vars) throws Exception {
        return perform(MockMvcRequest.of(MockMvcRequestBuilders.post(path, vars)).json(json), HttpMethod.POST);
    }

    public ResultActions put(String path, String json, Object... vars) throws Exception {
        return perform(MockMvcRequest.of(MockMvcRequestBuilders.put(path, vars)).json(json), HttpMethod.PUT);
    }

    public ResultActions patch(String path, String json, Object... vars) throws Exception {
        return perform(MockMvcRequest.of(MockMvcRequestBuilders.patch(path, vars)).json(json), HttpMethod.PATCH);
    }

    public ResultActions delete(String path, Object... vars) throws Exception {
        return perform(MockMvcRequest.of(MockMvcRequestBuilders.delete(path, vars)), HttpMethod.DELETE);
    }

    /** Sends a custom request (e.g. multipart) with this client's cookies and CSRF token. */
    public ResultActions perform(AbstractMockHttpServletRequestBuilder<?> request, HttpMethod method) throws Exception {
        return perform(MockMvcRequest.of(request), method);
    }

    private ResultActions perform(MockMvcRequest request, HttpMethod method) throws Exception {
        AbstractMockHttpServletRequestBuilder<?> builder = request.builder();
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        } else {
            if (!SAFE.contains(method)) {
                Cookie csrf = cookies.get("XSRF-TOKEN");
                if (csrf == null) {
                    fetchCsrf();
                    csrf = cookies.get("XSRF-TOKEN");
                }
                builder.header("X-XSRF-TOKEN", csrf.getValue());
            }
            if (!cookies.isEmpty()) {
                builder.cookie(cookies.values().toArray(Cookie[]::new));
            }
        }
        ResultActions actions = mvc.perform(builder);
        remember(actions.andReturn());
        return actions;
    }

    public void fetchCsrf() throws Exception {
        AbstractMockHttpServletRequestBuilder<?> builder = MockMvcRequestBuilders.get("/api/v1/auth/csrf");
        if (!cookies.isEmpty()) {
            builder.cookie(cookies.values().toArray(Cookie[]::new));
        }
        remember(mvc.perform(builder).andReturn());
        if (!cookies.containsKey("XSRF-TOKEN")) {
            throw new IllegalStateException("No CSRF cookie issued");
        }
    }

    private void remember(MvcResult result) {
        for (Cookie cookie : result.getResponse().getCookies()) {
            if (cookie.getMaxAge() == 0 || cookie.getValue() == null || cookie.getValue().isEmpty()) {
                cookies.remove(cookie.getName());
            } else {
                cookies.put(cookie.getName(), new Cookie(cookie.getName(), cookie.getValue()));
            }
        }
    }

    public Cookie cookie(String name) {
        return cookies.get(name);
    }

    public void setCookie(Cookie cookie) {
        cookies.put(cookie.getName(), cookie);
    }

    public void forgetCookie(String name) {
        cookies.remove(name);
    }

    private record MockMvcRequest(AbstractMockHttpServletRequestBuilder<?> builder) {
        static MockMvcRequest of(AbstractMockHttpServletRequestBuilder<?> builder) {
            return new MockMvcRequest(builder);
        }

        MockMvcRequest json(String body) {
            if (body != null) {
                builder.contentType(MediaType.APPLICATION_JSON).content(body);
            }
            return this;
        }
    }
}
