package com.jtac.identity.internal;

import com.jtac.audit.AuditEntry;
import com.jtac.audit.AuditLog;
import com.jtac.identity.internal.SsoSignInException.Reason;
import com.jtac.shared.Ids;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enterprise Single Sign-On policy: maps an identity provider's user to a JTRAC account.
 *
 * <ol>
 *   <li>A known (provider, subject) pair signs in to the account it is linked to.</li>
 *   <li>Otherwise an account with the same <em>verified</em> email is linked. Linking an
 *       unconfirmed account also confirms it and replaces its password, so a stranger who
 *       registered the address first cannot keep access.</li>
 *   <li>Otherwise, only with {@code jtac.sso.auto-provision}, a new account is created; it never
 *       becomes a server administrator.</li>
 * </ol>
 * Disabled accounts, unverified emails and domains outside {@code jtac.sso.allowed-domains}
 * are refused, as is any SSO sign-in before first-run setup.
 */
@Service
class EnterpriseSsoService {

    private static final Logger log = LoggerFactory.getLogger(EnterpriseSsoService.class);

    private final UserAccountRepository accounts;
    private final UserExternalIdentityRepository externalIdentities;
    private final UserAccountService accountService;
    private final ObjectProvider<ClientRegistrationRepository> registrations;
    private final SsoProperties properties;
    private final AuditLog audit;
    private final Clock clock;

    EnterpriseSsoService(UserAccountRepository accounts, UserExternalIdentityRepository externalIdentities,
            UserAccountService accountService, ObjectProvider<ClientRegistrationRepository> registrations,
            SsoProperties properties, AuditLog audit, Clock clock) {
        this.accounts = accounts;
        this.externalIdentities = externalIdentities;
        this.accountService = accountService;
        this.registrations = registrations;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    /** A configured identity provider, as offered on the sign-in page. */
    record SsoProvider(String id, String name) {

        String loginUrl() {
            return "/oauth2/authorization/" + id;
        }
    }

    /** Identity providers offered for sign-in; empty while SSO is disabled or unconfigured. */
    List<SsoProvider> providers() {
        List<SsoProvider> providers = new ArrayList<>();
        if (properties.enabled() && registrations.getIfAvailable() instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item instanceof ClientRegistration registration) {
                    String name = registration.getClientName();
                    providers.add(new SsoProvider(registration.getRegistrationId(),
                            name == null || name.isBlank() ? registration.getRegistrationId() : name));
                }
            }
        }
        return List.copyOf(providers);
    }

    boolean available() {
        return !providers().isEmpty();
    }

    /**
     * Signs in the identity provider's {@code user} authenticated by registration {@code provider}.
     *
     * @throws SsoSignInException if the sign-in is refused by policy (nothing is written)
     */
    @Transactional
    JtacUserDetails signIn(String provider, OAuth2User user) {
        if (!accountService.anyAccountExists()) {
            throw new SsoSignInException(Reason.SETUP_REQUIRED, "First-run setup is not complete");
        }
        String subject = subjectOf(user);
        String email = verifiedEmailOf(user);
        if (!properties.allowsDomainOf(email)) {
            throw new SsoSignInException(Reason.DOMAIN_NOT_ALLOWED, "Email domain is not allowed for SSO");
        }
        Instant now = clock.instant();

        UserAccount account;
        Optional<UserExternalIdentity> linked = externalIdentities.findByProviderAndSubjectId(provider, subject);
        if (linked.isPresent()) {
            UserExternalIdentity identity = linked.get();
            account = accounts.findById(identity.userId()).orElseThrow();
            requireActive(account);
            identity.recordLogin(email, now);
        } else {
            Optional<UserAccount> byEmail = accounts.findByEmail(email);
            if (byEmail.isPresent()) {
                account = byEmail.get();
                requireActive(account);
                if (externalIdentities.existsByUserIdAndProvider(account.id(), provider)) {
                    // Same email, different subject: most often an address reassigned at the provider.
                    throw new SsoSignInException(Reason.IDENTITY_CONFLICT,
                            "Account is already linked to another identity at this provider");
                }
                if (!account.isVerified()) {
                    accountService.confirmForSso(account);
                }
                externalIdentities.save(new UserExternalIdentity(Ids.newId(), account.id(), provider, subject, email, now));
                audit.record(AuditEntry.of(null, account.id(), "auth.sso_linked").target("user", account.id())
                        .detail("provider", provider));
                log.info("SSO identity linked to existing account userId={} provider={}", account.id(), provider);
            } else {
                if (!properties.autoProvision()) {
                    throw new SsoSignInException(Reason.NO_ACCOUNT, "No account matches and auto-provisioning is off");
                }
                account = accountService.provisionExternalUser(email, displayNameOf(user));
                externalIdentities.save(new UserExternalIdentity(Ids.newId(), account.id(), provider, subject, email, now));
                audit.record(AuditEntry.of(null, account.id(), "auth.sso_provisioned").target("user", account.id())
                        .detail("provider", provider));
                log.info("SSO provisioned account userId={} provider={}", account.id(), provider);
            }
        }

        accounts.recordLogin(account.id(), now);
        audit.record(AuditEntry.of(null, account.id(), "auth.signed_in").target("user", account.id())
                .detail("method", "sso").detail("provider", provider));
        log.info("SSO sign-in succeeded userId={} provider={}", account.id(), provider);
        return JtacUserDetails.from(account);
    }

    private static void requireActive(UserAccount account) {
        if (!account.isActive()) {
            throw new SsoSignInException(Reason.ACCOUNT_DISABLED, "Account is disabled");
        }
    }

    private static String subjectOf(OAuth2User user) {
        String subject = user instanceof OidcUser oidc ? oidc.getSubject() : text(user.getAttribute("sub"));
        if (subject == null || subject.isBlank()) {
            subject = user.getName();
        }
        if (subject == null || subject.isBlank() || subject.length() > 255) {
            throw new SsoSignInException(Reason.INVALID_IDENTITY, "Identity provider sent no usable subject");
        }
        return subject;
    }

    /**
     * The {@code email} claim, accepted only with {@code email_verified=true} unless the
     * operator trusts the provider's emails; then {@code upn} / {@code preferred_username}
     * (directory sign-in names) are accepted as a fallback too.
     */
    private String verifiedEmailOf(OAuth2User user) {
        String email = text(user.getAttribute("email"));
        boolean verified = Boolean.TRUE.equals(user.getAttribute("email_verified"))
                || "true".equalsIgnoreCase(text(user.getAttribute("email_verified")));
        if (properties.trustUnverifiedEmail()) {
            if (email == null) {
                email = firstAddress(text(user.getAttribute("upn")), text(user.getAttribute("preferred_username")));
            }
            verified = true;
        }
        if (email == null || !verified) {
            throw new SsoSignInException(Reason.EMAIL_UNVERIFIED, "Identity provider sent no verified email");
        }
        try {
            return accountService.requireValidEmail("email", email).toLowerCase(Locale.ROOT);
        } catch (RuntimeException ex) {
            throw new SsoSignInException(Reason.EMAIL_UNVERIFIED, "Identity provider sent an invalid email");
        }
    }

    private static String displayNameOf(OAuth2User user) {
        String name = text(user.getAttribute("name"));
        if (name != null) {
            return name;
        }
        String given = text(user.getAttribute("given_name"));
        String family = text(user.getAttribute("family_name"));
        if (given != null && family != null) {
            return given + " " + family;
        }
        return given != null ? given : family;
    }

    private static String firstAddress(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && candidate.contains("@")) {
                return candidate;
            }
        }
        return null;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().strip();
        return text.isEmpty() ? null : text;
    }
}
