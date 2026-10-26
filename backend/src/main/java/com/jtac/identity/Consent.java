package com.jtac.identity;

import com.jtac.shared.web.ApiErrors;

/** The rule every account-creating flow applies before it creates an account. */
public final class Consent {

    private Consent() {
    }

    /** Fails with a field error unless the person accepted the Terms of Service and Privacy Notice. */
    public static void require(Boolean acceptedTerms) {
        if (!Boolean.TRUE.equals(acceptedTerms)) {
            throw ApiErrors.invalid("acceptTerms", "Accept the Terms of Service and Privacy Notice to continue.");
        }
    }
}
