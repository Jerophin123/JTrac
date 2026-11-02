package com.jtac.identity.internal;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * Turns a completed OpenID Connect sign-in into a JTRAC session: the identity provider's
 * user is mapped by {@link EnterpriseSsoService}, and the session then holds the same
 * principal as a password sign-in. A refused sign-in goes to the failure handler, which
 * also discards the provider's authentication.
 */
@Component
class EnterpriseOAuth2SuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(EnterpriseOAuth2SuccessHandler.class);

    private final EnterpriseSsoService sso;
    private final EnterpriseOAuth2FailureHandler failure;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final SecurityContextRepository securityContextRepository;
    private final SsoUrls urls;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    EnterpriseOAuth2SuccessHandler(EnterpriseSsoService sso, EnterpriseOAuth2FailureHandler failure,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            SecurityContextRepository securityContextRepository, SsoUrls urls) {
        this.sso = sso;
        this.failure = failure;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.securityContextRepository = securityContextRepository;
        this.urls = urls;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        JtacUserDetails principal;
        try {
            if (!(authentication instanceof OAuth2AuthenticationToken token)) {
                throw new AuthenticationServiceException("Unexpected authentication type");
            }
            principal = sso.signIn(token.getAuthorizedClientRegistrationId(), token.getPrincipal());
        } catch (AuthenticationException ex) {
            failure.onAuthenticationFailure(request, response, ex);
            return;
        } catch (RuntimeException ex) {
            log.error("SSO sign-in could not be completed", ex);
            failure.onAuthenticationFailure(request, response,
                    new AuthenticationServiceException("SSO sign-in could not be completed", ex));
            return;
        }

        // Same session shape as a password sign-in: new session id, fresh CSRF token, JTRAC principal.
        Authentication session = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                principal.getAuthorities());
        sessionAuthenticationStrategy.onAuthentication(session, request, response);
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(session);
        contextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        response.sendRedirect(urls.page(takeReturnPath(request)));
    }

    private static String takeReturnPath(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return "/";
        }
        Object path = session.getAttribute(SsoAuthorizationRequestResolver.RETURN_PATH);
        session.removeAttribute(SsoAuthorizationRequestResolver.RETURN_PATH);
        String safe = path instanceof String text ? SsoUrls.safeReturnPath(text) : null;
        return safe == null ? "/" : safe;
    }
}
