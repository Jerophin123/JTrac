package com.jtac.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jtac.identity.internal.SsoSignInException.Reason;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/** SSO rules that need no database; the account mapping is covered by {@link EnterpriseSsoTests}. */
class SsoUnitTests {

    private final SsoUrls urls = new SsoUrls("https://tracker.example.com/");

    @Test
    void returnPathsMustBeSameOriginAndNotTheSignInFlow() {
        assertThat(SsoUrls.safeReturnPath("/orgs/acme/issues?q=open#top")).isEqualTo("/orgs/acme/issues?q=open#top");
        assertThat(SsoUrls.safeReturnPath("/")).isEqualTo("/");
        for (String unsafe : List.of("https://evil.example/", "//evil.example", "/\\evil.example", "orgs", "",
                "/login", "/login?sso=failed", "/oauth2/authorization/x", "/login/oauth2/code/x", "/a\nb",
                "/" + "a".repeat(2048))) {
            assertThat(SsoUrls.safeReturnPath(unsafe)).as(unsafe).isNull();
        }
        assertThat(SsoUrls.safeReturnPath(null)).isNull();
    }

    @Test
    void urlsAreBuiltFromThePublicUrl() {
        assertThat(urls.callback("entra")).isEqualTo("https://tracker.example.com/login/oauth2/code/entra");
        assertThat(urls.page("/login?sso=failed")).isEqualTo("https://tracker.example.com/login?sso=failed");
    }

    @Test
    void allowedDomainsAreNormalizedAndMatchedExactly() {
        SsoProperties any = new SsoProperties(true, false, List.of("", " "), false);
        assertThat(any.allowedDomains()).isEmpty();
        assertThat(any.allowsDomainOf("someone@anywhere.test")).isTrue();

        SsoProperties restricted = new SsoProperties(true, false, List.of(" Example.COM "), false);
        assertThat(restricted.allowsDomainOf("ada@EXAMPLE.com")).isTrue();
        assertThat(restricted.allowsDomainOf("ada@sub.example.com")).isFalse();
        assertThat(restricted.allowsDomainOf("ada@example.com.evil.test")).isFalse();
    }

    @Test
    void failuresMapToFixedCodes() {
        assertThat(EnterpriseOAuth2FailureHandler.codeFor(new SsoSignInException(Reason.NO_ACCOUNT, "x")))
                .isEqualTo("no_account");
        assertThat(EnterpriseOAuth2FailureHandler.codeFor(
                new OAuth2AuthenticationException(new OAuth2Error("access_denied", "User said no", null))))
                .isEqualTo("cancelled");
        assertThat(EnterpriseOAuth2FailureHandler.codeFor(
                new OAuth2AuthenticationException(new OAuth2Error("invalid_id_token", "<script>", null))))
                .isEqualTo("failed");
        assertThat(EnterpriseOAuth2FailureHandler.codeFor(new BadCredentialsException("x"))).isEqualTo("failed");
    }

    @Test
    void authorizationRequestPinsPublicCallbackAndKeepsSafeReturnPath() {
        SsoAuthorizationRequestResolver resolver = new SsoAuthorizationRequestResolver(
                new InMemoryClientRegistrationRepository(registration("entra", "{baseUrl}/{action}/oauth2/code/{registrationId}")),
                urls);
        // The request arrives through a proxy with a different host.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorization/entra");
        request.setServletPath("/oauth2/authorization/entra");
        request.setServerName("localhost");
        request.setServerPort(8080);
        request.setParameter("redirect", "/orgs/acme");

        OAuth2AuthorizationRequest authorization = resolver.resolve(request);

        assertThat(authorization).isNotNull();
        assertThat(authorization.getRedirectUri()).isEqualTo("https://tracker.example.com/login/oauth2/code/entra");
        assertThat(authorization.getState()).isNotBlank();
        assertThat(authorization.getAttributes()).containsKey("nonce");
        assertThat(request.getSession().getAttribute(SsoAuthorizationRequestResolver.RETURN_PATH)).isEqualTo("/orgs/acme");
    }

    @Test
    void explicitlyConfiguredRedirectUriIsKept() {
        SsoAuthorizationRequestResolver resolver = new SsoAuthorizationRequestResolver(
                new InMemoryClientRegistrationRepository(registration("okta", "https://sso.example.com/login/oauth2/code/okta")),
                urls);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorization/okta");
        request.setServletPath("/oauth2/authorization/okta");
        request.setParameter("redirect", "https://evil.example/");

        OAuth2AuthorizationRequest authorization = resolver.resolve(request);

        assertThat(authorization.getRedirectUri()).isEqualTo("https://sso.example.com/login/oauth2/code/okta");
        assertThat(request.getSession(false)).isNull();
    }

    private static ClientRegistration registration(String id, String redirectUri) {
        return ClientRegistration.withRegistrationId(id)
                .clientId("jtrac")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri)
                .scope("openid", "email", "profile")
                .authorizationUri("https://idp.example.test/authorize")
                .tokenUri("https://idp.example.test/token")
                .jwkSetUri("https://idp.example.test/jwks")
                .userNameAttributeName("sub")
                .build();
    }
}
