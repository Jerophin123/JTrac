package com.jtac.identity.internal;

import com.jtac.audit.AuditEntry;
import com.jtac.audit.AuditLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * Ends a failed or refused SSO sign-in: discards any authentication the provider produced and
 * redirects to {@code /login?sso=<code>}. Only a fixed code reaches the browser, never the
 * provider's or the exception's text, so the sign-in page cannot be made to show arbitrary messages.
 */
@Component
class EnterpriseOAuth2FailureHandler implements AuthenticationFailureHandler {

    /** The user declined consent or cancelled at the identity provider. */
    static final String CANCELLED = "cancelled";
    /** Any other failure (provider error, invalid token, unexpected error). */
    static final String FAILED = "failed";

    private static final Logger log = LoggerFactory.getLogger(EnterpriseOAuth2FailureHandler.class);

    private final SecurityContextRepository securityContextRepository;
    private final AuditLog audit;
    private final SsoUrls urls;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    EnterpriseOAuth2FailureHandler(SecurityContextRepository securityContextRepository, AuditLog audit, SsoUrls urls) {
        this.securityContextRepository = securityContextRepository;
        this.audit = audit;
        this.urls = urls;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        // The login filter may already have stored the provider's authentication in the session.
        contextHolder.clearContext();
        securityContextRepository.saveContext(contextHolder.createEmptyContext(), request, response);
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(SsoAuthorizationRequestResolver.RETURN_PATH);
        }

        String code = codeFor(exception);
        log.warn("SSO sign-in failed: {} ({})", code, exception.getClass().getSimpleName());
        audit.record(AuditEntry.of(null, null, "auth.sign_in_failed").detail("method", "sso").detail("reason", code));
        response.sendRedirect(urls.page("/login?sso=" + code));
    }

    static String codeFor(AuthenticationException exception) {
        if (exception instanceof SsoSignInException refused) {
            return refused.reason().code();
        }
        if (exception instanceof OAuth2AuthenticationException oauth
                && OAuth2ErrorCodes.ACCESS_DENIED.equals(oauth.getError().getErrorCode())) {
            return CANCELLED;
        }
        return FAILED;
    }
}
