package com.jtac.identity.internal;

import com.jtac.audit.AuditEntry;
import com.jtac.audit.AuditLog;
import com.jtac.shared.web.ApiProblemException;
import com.jtac.shared.web.ProblemTypes;
import java.io.Serial;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

/**
 * Sign-in policy: throttling, password-length rule, credential check, verified-email
 * requirement and sign-in bookkeeping. Web concerns (session creation, CSRF rotation)
 * stay in the controller, so every sign-in channel goes through the same rules.
 */
@Service
class SignInService {

    private static final Logger log = LoggerFactory.getLogger(SignInService.class);

    private final AuthenticationManager authenticationManager;
    private final LoginThrottle throttle;
    private final UserAccountService accounts;
    private final AuditLog audit;

    SignInService(AuthenticationManager authenticationManager, LoginThrottle throttle, UserAccountService accounts,
            AuditLog audit) {
        this.authenticationManager = authenticationManager;
        this.throttle = throttle;
        this.accounts = accounts;
        this.audit = audit;
    }

    /**
     * Verifies the credentials of one sign-in attempt from {@code clientAddress}.
     *
     * @throws LoginThrottledException if the attempt is rate limited (nothing is checked)
     * @throws BadCredentialsException for any unsuccessful attempt: unknown, disabled and
     *         wrong-password accounts are indistinguishable to the caller
     * @throws EmailNotVerified if the credentials are correct but the email is unverified
     */
    Authentication signIn(String email, String password, String clientAddress) {
        String accountKey = UserAccountService.throttleKey(email);
        throttle.tryAcquire(accountKey, clientAddress).ifPresent(wait -> {
            log.warn("Sign-in throttled");
            throw new LoginThrottledException(wait);
        });

        Authentication authentication;
        try {
            if (UserAccountService.exceedsPasswordLimit(password)) {
                throw new BadCredentialsException("Password exceeds the supported length");
            }
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(email.strip(), password));
        } catch (AuthenticationException ex) {
            log.warn("Sign-in failed: {}", ex.getClass().getSimpleName());
            audit.record(AuditEntry.of(null, null, "auth.sign_in_failed").detail("reason", ex.getClass().getSimpleName()));
            throw new BadCredentialsException("Invalid credentials", ex);
        }

        throttle.succeeded(accountKey, clientAddress);
        JtacUserDetails principal = (JtacUserDetails) authentication.getPrincipal();
        if (!principal.verified()) {
            throw new EmailNotVerified();
        }
        accounts.recordLogin(principal.id());
        audit.record(AuditEntry.of(null, principal.id(), "auth.signed_in").target("user", principal.id()));
        log.info("Sign-in succeeded userId={}", principal.id());
        return authentication;
    }

    static final class EmailNotVerified extends ApiProblemException {
        @Serial
        private static final long serialVersionUID = 1L;

        EmailNotVerified() {
            super(HttpStatus.FORBIDDEN, ProblemTypes.EMAIL_NOT_VERIFIED, "Email address not confirmed",
                    "Confirm your email address using the link we sent you, or request a new link.");
        }
    }
}
