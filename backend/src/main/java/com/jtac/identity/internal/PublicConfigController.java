package com.jtac.identity.internal;

import com.jtac.mail.Mailer;
import com.jtac.shared.web.ApiPaths;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Non-sensitive configuration the sign-in screens need before authentication. */
@RestController
class PublicConfigController {

    private final AccountRecoveryService recovery;
    private final LegalProperties legal;
    private final Mailer mailer;
    private final EnterpriseSsoService sso;

    PublicConfigController(AccountRecoveryService recovery, LegalProperties legal, Mailer mailer,
            EnterpriseSsoService sso) {
        this.recovery = recovery;
        this.legal = legal;
        this.mailer = mailer;
        this.sso = sso;
    }

    @GetMapping(ApiPaths.V1 + "/config")
    PublicConfig config() {
        List<SsoProvider> providers = sso.providers().stream()
                .map(provider -> new SsoProvider(provider.id(), provider.name(), provider.loginUrl()))
                .toList();
        return new PublicConfig(recovery.registrationOpen(), mailer.deliveryEnabled(), providers,
                new Legal(legal.version(), legal.effectiveDate(),
                legal.operatorName(), legal.operatorAddress(), legal.contactEmail(), legal.grievanceOfficerName(),
                legal.grievanceOfficerEmail(), legal.jurisdiction()));
    }

    /**
     * @param emailEnabled false while outgoing email is not configured (password resets can't be emailed)
     * @param ssoProviders enterprise identity providers offered for sign-in (empty: SSO is off)
     */
    record PublicConfig(boolean registrationOpen, boolean emailEnabled, List<SsoProvider> ssoProviders, Legal legal) {
    }

    /** @param loginUrl starts the sign-in; append {@code ?redirect=/path} to return there afterwards */
    record SsoProvider(String id, String name, String loginUrl) {
    }

    /** Operator details for the Terms of Service, Privacy Notice and grievance page; blank when not configured. */
    record Legal(String version, String effectiveDate, String operatorName, String operatorAddress, String contactEmail,
            String grievanceOfficerName, String grievanceOfficerEmail, String jurisdiction) {
    }
}
