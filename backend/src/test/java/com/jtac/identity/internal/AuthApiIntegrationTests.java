package com.jtac.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jtac.DatabaseIntegrationTest;
import com.jtac.support.TestClient;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * End-to-end tests of the session authentication API through the real Spring Security
 * filter chain, CSRF protection and the jtac_test database. The CSRF token is obtained
 * the way the SPA obtains it (cookie from GET /csrf, echoed in X-XSRF-TOKEN); sessions are
 * stored in PostgreSQL by Spring Session and identified by the JTAC_SESSION cookie.
 */
@DatabaseIntegrationTest
class AuthApiIntegrationTests {

    private static final String AUTH = "/api/v1/auth";
    private static final String PROBLEM_JSON = "application/problem+json";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserAccountService accounts;

    private String email;
    private String password;

    @BeforeEach
    void createAccount() {
        email = "it-" + UUID.randomUUID() + "@jtac.test";
        password = "Pw-" + UUID.randomUUID();        // generated per test, never logged
        accounts.create(email, "Integration Tester", password, true);
    }

    @AfterEach
    void deleteAccount() {
        accounts.deleteByEmail(email);
    }

    @Test
    void sessionRequiresAuthentication() throws Exception {
        mvc.perform(get(AUTH + "/session"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:jtac:problem:unauthenticated"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.requestId").value(notNullValue()))
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void csrfEndpointIssuesAReadableTokenCookie() throws Exception {
        MvcResult result = mvc.perform(get(AUTH + "/csrf"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isNotBlank();
        assertThat(cookie.isHttpOnly()).as("the SPA must be able to read the CSRF cookie").isFalse();
    }

    @Test
    void loginWithoutCsrfTokenIsRejected() throws Exception {
        mvc.perform(post(AUTH + "/login").contentType(MediaType.APPLICATION_JSON).content(credentials(email, password)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:jtac:problem:csrf-rejected"));
    }

    @Test
    void loginWithAForgedCsrfHeaderIsRejected() throws Exception {
        Cookie csrf = fetchCsrfCookie();
        mvc.perform(post(AUTH + "/login").cookie(csrf).header("X-XSRF-TOKEN", "forged-" + csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(credentials(email, password)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:jtac:problem:csrf-rejected"));
    }

    @Test
    void wrongPasswordAndUnknownAccountGetTheSameGenericAnswer() throws Exception {
        MvcResult wrongPassword = mvc.perform(withCsrf(post(AUTH + "/login"))
                        .contentType(MediaType.APPLICATION_JSON).content(credentials(email, password + "x")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:jtac:problem:invalid-credentials"))
                .andReturn();
        MvcResult unknownAccount = mvc.perform(withCsrf(post(AUTH + "/login"))
                        .contentType(MediaType.APPLICATION_JSON).content(credentials("nobody-" + email, password)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:jtac:problem:invalid-credentials"))
                .andReturn();
        assertThat(detailOf(wrongPassword)).isEqualTo(detailOf(unknownAccount));
    }

    @Test
    void successfulLoginCreatesANewSessionAndRotatesTheCsrfToken() throws Exception {
        TestClient browser = new TestClient(mvc);
        browser.login(email, password).andExpect(status().isOk());
        Cookie first = browser.cookie(TestClient.SESSION_COOKIE);
        assertThat(first).as("sessions are cookie-based").isNotNull();

        // Sign in again while presenting the existing session: a new session id is issued.
        MvcResult login = browser.login(email.toUpperCase(), password)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.displayName").value("Integration Tester"))
                .andExpect(jsonPath("$.user.id").value(notNullValue()))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andReturn();
        Cookie second = browser.cookie(TestClient.SESSION_COOKIE);
        assertThat(second).isNotNull();
        assertThat(second.getValue()).as("session fixation protection").isNotEqualTo(first.getValue());
        Cookie clearedCsrf = login.getResponse().getCookie("XSRF-TOKEN");
        assertThat(clearedCsrf).as("CSRF token is cleared after authentication").isNotNull();
        assertThat(clearedCsrf.getMaxAge()).isZero();

        browser.get(AUTH + "/session").andExpect(status().isOk()).andExpect(jsonPath("$.user.email").value(email));

        // The previous session id is no longer valid.
        TestClient stale = new TestClient(mvc);
        stale.setCookie(first);
        stale.get(AUTH + "/session").andExpect(status().isUnauthorized());
    }

    @Test
    void sessionCookieIsHttpOnlyAndSameSite() throws Exception {
        MvcResult login = mvc.perform(withCsrf(post(AUTH + "/login")).contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(email, password)))
                .andExpect(status().isOk())
                .andReturn();
        String setCookie = login.getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith(TestClient.SESSION_COOKIE + "=")).findFirst().orElseThrow();
        assertThat(setCookie).contains("HttpOnly").containsIgnoringCase("SameSite=Lax");
    }

    @Test
    void logoutRequiresCsrfAndEndsTheSession() throws Exception {
        TestClient browser = new TestClient(mvc);
        browser.login(email, password).andExpect(status().isOk());
        Cookie session = browser.cookie(TestClient.SESSION_COOKIE);

        mvc.perform(post(AUTH + "/logout").cookie(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:jtac:problem:csrf-rejected"));

        browser.post(AUTH + "/logout", null).andExpect(status().isNoContent());

        TestClient stale = new TestClient(mvc);
        stale.setCookie(session);
        stale.get(AUTH + "/session").andExpect(status().isUnauthorized());
    }

    @Test
    void invalidInputReturnsFieldErrors() throws Exception {
        mvc.perform(withCsrf(post(AUTH + "/login")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:jtac:problem:validation-failed"))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("email")))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("password")));
    }

    @Test
    void malformedJsonDoesNotLeakParserDetails() throws Exception {
        mvc.perform(withCsrf(post(AUTH + "/login")).contentType(MediaType.APPLICATION_JSON).content("{\"email\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:jtac:problem:malformed-request"))
                .andExpect(jsonPath("$.detail").value("The request body or parameters could not be read."));
    }

    @Test
    void repeatedFailuresAreThrottled() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(withCsrf(post(AUTH + "/login")).contentType(MediaType.APPLICATION_JSON)
                            .content(credentials(email, "wrong-password-" + i)))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(withCsrf(post(AUTH + "/login")).contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(email, password)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.type").value("urn:jtac:problem:rate-limited"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(notNullValue()));
    }

    @Test
    void aDeletedAccountLosesItsSessionImmediately() throws Exception {
        TestClient browser = new TestClient(mvc);
        browser.login(email, password).andExpect(status().isOk());
        browser.get(AUTH + "/session").andExpect(status().isOk());

        assertThat(accounts.deleteByEmail(email)).isTrue();

        browser.get(AUTH + "/session")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:jtac:problem:unauthenticated"));
    }

    @Test
    void emailLookupIsCaseInsensitiveAndUsesTheDatabaseNormalization() throws Exception {
        mvc.perform(withCsrf(post(AUTH + "/login")).contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("  " + email.toUpperCase() + " ", password)))
                .andExpect(status().isBadRequest()); // surrounding whitespace is not a valid address
        mvc.perform(withCsrf(post(AUTH + "/login")).contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(email.toUpperCase(), password)))
                .andExpect(status().isOk());
    }

    @Test
    void concurrentSignInsOfTheSameUserAllSucceed() throws Exception {
        // Below the per-(account, client) limit of 5 in-flight attempts; exercises concurrent recordLogin.
        int attempts = 4;
        try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                results.add(pool.submit(() -> mvc.perform(withCsrf(post(AUTH + "/login"))
                                .contentType(MediaType.APPLICATION_JSON).content(credentials(email, password)))
                        .andReturn().getResponse().getStatus()));
            }
            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(200);
            }
        }
    }

    @Test
    void unknownApiRouteIsAProblemNotAnHtmlPage() throws Exception {
        mvc.perform(get("/api/v1/does-not-exist").cookie(login()))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:jtac:problem:not-found"));
    }

    private Cookie login() throws Exception {
        TestClient browser = new TestClient(mvc);
        browser.login(email, password).andExpect(status().isOk());
        return browser.cookie(TestClient.SESSION_COOKIE);
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) throws Exception {
        Cookie csrf = fetchCsrfCookie();
        return request.cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
    }

    private Cookie fetchCsrfCookie() throws Exception {
        Cookie cookie = mvc.perform(get(AUTH + "/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private static String credentials(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private static String detailOf(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        return body.replaceAll(".*\"detail\":\"([^\"]*)\".*", "$1");
    }
}
