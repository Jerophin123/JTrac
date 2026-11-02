package com.jtac.identity.internal;

import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Enterprise Single Sign-On (OpenID Connect) policy. The identity providers themselves are
 * standard Spring Security client registrations ({@code spring.security.oauth2.client.*});
 * SSO is offered only while at least one is configured (docs/sso.md).
 *
 * @param enabled              master switch; {@code false} ignores configured registrations
 * @param autoProvision        create an account on first sign-in when none matches the email
 *                             (off by default: the registration mode stays authoritative)
 * @param allowedDomains       email domains accepted from the identity provider; empty accepts any
 * @param trustUnverifiedEmail treat the email claim as verified even without {@code email_verified=true}.
 *                             Only for a single-tenant provider whose users cannot choose their email.
 */
@ConfigurationProperties("jtac.sso")
record SsoProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("false") boolean autoProvision,
        @DefaultValue List<String> allowedDomains,
        @DefaultValue("false") boolean trustUnverifiedEmail) {

    SsoProperties {
        allowedDomains = allowedDomains.stream()
                .map(domain -> domain.strip().toLowerCase(Locale.ROOT))
                .filter(domain -> !domain.isEmpty())
                .toList();
    }

    boolean allowsDomainOf(String email) {
        if (allowedDomains.isEmpty()) {
            return true;
        }
        String domain = email.substring(email.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT);
        return allowedDomains.contains(domain);
    }
}
