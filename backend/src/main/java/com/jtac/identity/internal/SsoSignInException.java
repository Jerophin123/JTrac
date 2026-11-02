package com.jtac.identity.internal;

import java.io.Serial;
import org.springframework.security.core.AuthenticationException;

/**
 * An SSO sign-in refused by JTRAC's policy. The {@link Reason#code() code} is the only detail
 * that reaches the browser (as {@code /login?sso=<code>}); the message is for logs.
 */
final class SsoSignInException extends AuthenticationException {

    @Serial
    private static final long serialVersionUID = 1L;

    enum Reason {
        /** The identity provider sent no usable subject identifier. */
        INVALID_IDENTITY("invalid_identity"),
        /** The identity provider sent no usable email claim, or did not verify it. */
        EMAIL_UNVERIFIED("email_unverified"),
        /** The email domain is not in {@code jtac.sso.allowed-domains}. */
        DOMAIN_NOT_ALLOWED("domain_not_allowed"),
        /** No account matches and automatic provisioning is off. */
        NO_ACCOUNT("no_account"),
        /** The matching account is disabled. */
        ACCOUNT_DISABLED("account_disabled"),
        /** The account is already linked to a different identity at this provider. */
        IDENTITY_CONFLICT("identity_conflict"),
        /** First-run setup has not been completed yet. */
        SETUP_REQUIRED("setup_required");

        private final String code;

        Reason(String code) {
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    private final Reason reason;

    SsoSignInException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }
}
