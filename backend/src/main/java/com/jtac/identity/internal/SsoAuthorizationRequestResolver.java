package com.jtac.identity.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;

/**
 * Starts an SSO sign-in ({@code GET /oauth2/authorization/{id}?redirect=/path}). Spring's
 * resolver supplies state, nonce and PKCE; this one pins the redirect URI to the public URL
 * (unless the registration sets its own) and remembers the validated return path in the session.
 */
final class SsoAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    static final String RETURN_PATH = SsoAuthorizationRequestResolver.class.getName() + ".RETURN_PATH";
    private static final String DEFAULT_REDIRECT_URI = "{baseUrl}/{action}/oauth2/code/{registrationId}";

    private final DefaultOAuth2AuthorizationRequestResolver delegate;
    private final ClientRegistrationRepository registrations;
    private final SsoUrls urls;

    SsoAuthorizationRequestResolver(ClientRegistrationRepository registrations, SsoUrls urls) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(registrations,
                DefaultOAuth2AuthorizationRequestResolver.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
        this.registrations = registrations;
        this.urls = urls;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return customize(request, delegate.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return customize(request, delegate.resolve(request, clientRegistrationId));
    }

    private OAuth2AuthorizationRequest customize(HttpServletRequest request, OAuth2AuthorizationRequest authorization) {
        if (authorization == null) {
            return null;
        }
        String returnPath = SsoUrls.safeReturnPath(request.getParameter("redirect"));
        if (returnPath != null) {
            request.getSession().setAttribute(RETURN_PATH, returnPath);
        } else {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.removeAttribute(RETURN_PATH);
            }
        }
        String registrationId = authorization.getAttribute(OAuth2ParameterNames.REGISTRATION_ID);
        ClientRegistration registration = registrations.findByRegistrationId(registrationId);
        if (registration == null || !DEFAULT_REDIRECT_URI.equals(registration.getRedirectUri())) {
            return authorization;
        }
        return OAuth2AuthorizationRequest.from(authorization).redirectUri(urls.callback(registrationId)).build();
    }
}
