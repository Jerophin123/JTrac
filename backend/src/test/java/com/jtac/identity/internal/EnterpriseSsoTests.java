package com.jtac.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jtac.DatabaseIntegrationTest;
import com.jtac.audit.AuditLog;
import com.jtac.identity.internal.SsoSignInException.Reason;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@DatabaseIntegrationTest
class EnterpriseSsoTests {

    private static final String PROVIDER = "testidp";
    private static final String PASSWORD = "Password-123456";

    @Autowired private MockMvc mvc;
    @Autowired private EnterpriseSsoService ssoService;
    @Autowired private EnterpriseOAuth2SuccessHandler successHandler;
    @Autowired private EnterpriseOAuth2FailureHandler failureHandler;
    @Autowired private UserAccountRepository accounts;
    @Autowired private UserExternalIdentityRepository externalIdentities;
    @Autowired private UserAccountService accountService;
    @Autowired private ObjectProvider<ClientRegistrationRepository> registrations;
    @Autowired private AuditLog audit;
    @Autowired private Clock clock;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcClient jdbc;

    @Autowired private SessionRepository<? extends Session> sessions;
    @Value("${server.servlet.session.cookie.name}") private String sessionCookieName;

    private final List<String> createdEmails = new ArrayList<>();
    private final List<String> createdSessions = new ArrayList<>();

    /**
     * SSO is refused until first-run setup has created an account; a fresh database (as in CI)
     * has none, so each test starts with one of its own instead of relying on other tests' data.
     */
    @BeforeEach
    void setupIsComplete() {
        localAccount(true);
    }

    @AfterEach
    void cleanup() {
        createdEmails.forEach(accountService::deleteByEmail);
        createdSessions.forEach(sessions::deleteById);
    }

    // --- Web wiring --------------------------------------------------------------------

    @Test
    void publicConfigListsConfiguredProviders() throws Exception {
        mvc.perform(get("/api/v1/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ssoProviders.length()").value(1))
                .andExpect(jsonPath("$.ssoProviders[0].id").value(PROVIDER))
                .andExpect(jsonPath("$.ssoProviders[0].name").value("Test IdP"))
                .andExpect(jsonPath("$.ssoProviders[0].loginUrl").value("/oauth2/authorization/" + PROVIDER));
    }

    @Test
    void authorizationRequestUsesPublicCallbackPkceAndRemembersReturnPath() throws Exception {
        MvcResult result = mvc.perform(get("/oauth2/authorization/" + PROVIDER).param("redirect", "/orgs/acme?tab=1"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).startsWith("https://idp.example.test/authorize?")
                .contains("redirect_uri=http://localhost:5173/login/oauth2/code/" + PROVIDER)
                .contains("code_challenge_method=S256")
                .contains("nonce=")
                .contains("state=");
        assertThat(storedSession(result).<String>getAttribute(SsoAuthorizationRequestResolver.RETURN_PATH))
                .isEqualTo("/orgs/acme?tab=1");
    }

    @Test
    void unsafeReturnPathsAreIgnored() throws Exception {
        for (String unsafe : List.of("https://evil.example", "//evil.example", "/\\evil.example", "/login")) {
            MvcResult result = mvc.perform(get("/oauth2/authorization/" + PROVIDER).param("redirect", unsafe))
                    .andExpect(status().is3xxRedirection())
                    .andReturn();
            assertThat(storedSession(result).<String>getAttribute(SsoAuthorizationRequestResolver.RETURN_PATH))
                    .as(unsafe).isNull();
        }
    }

    @Test
    void springDoesNotReplaceTheSpaLoginPage() throws Exception {
        String body = mvc.perform(get("/login")).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("/oauth2/authorization/" + PROVIDER);
    }

    @Test
    void successHandlerEstablishesJtracSessionAndReturnsToSavedPath() throws Exception {
        String email = localAccount(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/" + PROVIDER);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SsoAuthorizationRequestResolver.RETURN_PATH, "/my-work");
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();

        successHandler.onAuthenticationSuccess(request, response, token(user(newSubject(), email, true)));

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:5173/my-work");
        SecurityContext context = (SecurityContext) request.getSession()
                .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context.getAuthentication().getPrincipal()).isInstanceOf(JtacUserDetails.class);
        assertThat(((JtacUserDetails) context.getAuthentication().getPrincipal()).getUsername()).isEqualTo(email);
        assertThat(request.getSession().getAttribute(SsoAuthorizationRequestResolver.RETURN_PATH)).isNull();
    }

    @Test
    void refusedSignInDiscardsProviderAuthenticationAndRedirectsWithCode() throws Exception {
        String unknown = "nobody-" + UUID.randomUUID() + "@enterprise.test";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/" + PROVIDER);
        OAuth2AuthenticationToken token = token(user(newSubject(), unknown, true));
        // What the login filter stores before calling the success handler.
        request.getSession().setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(token));
        MockHttpServletResponse response = new MockHttpServletResponse();

        successHandler.onAuthenticationSuccess(request, response, token);

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:5173/login?sso=no_account");
        assertThat(request.getSession().getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .isNull();
        assertThat(accounts.findByEmail(unknown)).isEmpty();
    }

    @Test
    void failureHandlerNeverEchoesProviderText() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/" + PROVIDER);
        MockHttpServletResponse response = new MockHttpServletResponse();

        failureHandler.onAuthenticationFailure(request, response,
                new OAuth2AuthenticationException(new OAuth2Error("server_error", "<b>call 555</b>", null)));

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:5173/login?sso=failed");
    }

    // --- Account mapping policy ----------------------------------------------------------

    @Test
    void verifiedEmailLinksExistingAccountAndLaterSignInsUseTheLink() {
        String email = localAccount(true);
        UUID accountId = accounts.findByEmail(email).orElseThrow().id();
        String subject = newSubject();

        JtacUserDetails first = signIn(ssoService, user(subject, email, true));
        JtacUserDetails second = signIn(ssoService, user(subject, email, true));

        assertThat(first.id()).isEqualTo(accountId);
        assertThat(second.id()).isEqualTo(accountId);
        assertThat(externalIdentities.findByUserId(accountId)).singleElement()
                .satisfies(identity -> assertThat(identity.subjectId()).isEqualTo(subject));
    }

    @Test
    void unverifiedEmailClaimIsRefused() {
        String email = localAccount(true);
        UUID accountId = accounts.findByEmail(email).orElseThrow().id();

        assertRefused(ssoService, user(newSubject(), email, false), Reason.EMAIL_UNVERIFIED);
        assertRefused(ssoService, user(newSubject(), email, null), Reason.EMAIL_UNVERIFIED);
        assertThat(externalIdentities.findByUserId(accountId)).isEmpty();
    }

    @Test
    void trustedProviderMayOmitEmailVerified() {
        String email = localAccount(true);
        EnterpriseSsoService trusting = service(false, List.of(), true);

        assertThat(signIn(trusting, user(newSubject(), email, null)).getUsername()).isEqualTo(email);
    }

    @Test
    void unknownUserIsRefusedWhileAutoProvisioningIsOff() {
        String email = track("new-" + UUID.randomUUID() + "@enterprise.test");

        assertRefused(ssoService, user(newSubject(), email, true), Reason.NO_ACCOUNT);
        assertThat(accounts.findByEmail(email)).isEmpty();
    }

    @Test
    void autoProvisioningCreatesConfirmedNonAdministratorAccount() {
        String email = track("jit-" + UUID.randomUUID() + "@enterprise.test");
        String subject = newSubject();
        EnterpriseSsoService provisioning = service(true, List.of(), false);

        JtacUserDetails user = signIn(provisioning, user(subject, email, true));

        UserAccount account = accounts.findByEmail(email).orElseThrow();
        assertThat(user.id()).isEqualTo(account.id());
        assertThat(user.toCurrentUser().displayName()).isEqualTo("Enterprise Officer");
        assertThat(account.isVerified()).isTrue();
        assertThat(account.isServerAdmin()).isFalse();
        assertThat(externalIdentities.findByProviderAndSubjectId(PROVIDER, subject)).get()
                .satisfies(identity -> assertThat(identity.userId()).isEqualTo(account.id()));
    }

    @Test
    void emailOutsideAllowedDomainsIsRefused() {
        String email = track("jit-" + UUID.randomUUID() + "@elsewhere.test");
        EnterpriseSsoService restricted = service(true, List.of(" Enterprise.TEST "), false);

        assertRefused(restricted, user(newSubject(), email, true), Reason.DOMAIN_NOT_ALLOWED);
        assertThat(accounts.findByEmail(email)).isEmpty();
    }

    @Test
    void disabledAccountIsRefused() {
        String email = localAccount(true);
        jdbc.sql("update user_account set status = 'DISABLED' where email = :email").param("email", email).update();

        assertRefused(ssoService, user(newSubject(), email, true), Reason.ACCOUNT_DISABLED);
    }

    @Test
    void linkingAnUnconfirmedAccountConfirmsItAndRevokesItsPassword() {
        String email = localAccount(false);

        signIn(ssoService, user(newSubject(), email, true));

        UserAccount account = accounts.findByEmail(email).orElseThrow();
        assertThat(account.isVerified()).isTrue();
        assertThat(passwordEncoder.matches(PASSWORD, account.passwordHash())).isFalse();
    }

    @Test
    void secondSubjectWithSameEmailAtSameProviderIsRefused() {
        String email = localAccount(true);
        signIn(ssoService, user(newSubject(), email, true));

        assertRefused(ssoService, user(newSubject(), email, true), Reason.IDENTITY_CONFLICT);
    }

    // --- Helpers -------------------------------------------------------------------------

    /**
     * The server-side session the response's cookie points to. Sessions are kept by Spring
     * Session (JDBC), so MockMvc's own {@code MockHttpSession} never sees their attributes.
     */
    private Session storedSession(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie(sessionCookieName);
        assertThat(cookie).as("session cookie").isNotNull();
        createdSessions.add(cookie.getValue());
        Session session = sessions.findById(cookie.getValue());
        assertThat(session).as("stored session").isNotNull();
        return session;
    }

    private EnterpriseSsoService service(boolean autoProvision, List<String> domains, boolean trustUnverifiedEmail) {
        return new EnterpriseSsoService(accounts, externalIdentities, accountService, registrations,
                new SsoProperties(true, autoProvision, domains, trustUnverifiedEmail), audit, clock);
    }

    /** Runs a sign-in in a transaction, as the proxied service bean does. */
    private JtacUserDetails signIn(EnterpriseSsoService service, OAuth2User user) {
        return new TransactionTemplate(transactionManager).execute(status -> service.signIn(PROVIDER, user));
    }

    private void assertRefused(EnterpriseSsoService service, OAuth2User user, Reason reason) {
        assertThatThrownBy(() -> signIn(service, user))
                .isInstanceOfSatisfying(SsoSignInException.class, ex -> assertThat(ex.reason()).isEqualTo(reason));
    }

    private String localAccount(boolean verified) {
        String email = track("local-" + UUID.randomUUID() + "@enterprise.test");
        accountService.create(email, "Local User", PASSWORD, verified);
        return email;
    }

    private String track(String email) {
        createdEmails.add(email);
        return email;
    }

    private static String newSubject() {
        return "sub-" + UUID.randomUUID();
    }

    private static OAuth2User user(String subject, String email, Boolean emailVerified) {
        Map<String, Object> claims = new HashMap<>(Map.of("sub", subject, "email", email, "name", "Enterprise Officer"));
        if (emailVerified != null) {
            claims.put("email_verified", emailVerified);
        }
        return new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OIDC_USER")), claims, "sub");
    }

    private static OAuth2AuthenticationToken token(OAuth2User user) {
        return new OAuth2AuthenticationToken(user, user.getAuthorities(), PROVIDER);
    }
}
