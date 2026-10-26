package com.jtac.identity.internal;

import com.jtac.mail.Mailer;
import com.jtac.shared.web.ApiPaths;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Non-sensitive configuration the sign-in screens need before authentication. */
@RestController
class PublicConfigController {

    private final AccountRecoveryService recovery;
    private final LegalProperties legal;
    private final Mailer mailer;

    PublicConfigController(AccountRecoveryService recovery, LegalProperties legal, Mailer mailer) {
        this.recovery = recovery;
        this.legal = legal;
        this.mailer = mailer;
    }

    @GetMapping(ApiPaths.V1 + "/config")
    PublicConfig config() {
        return new PublicConfig(recovery.registrationOpen(), mailer.deliveryEnabled(), new Legal(legal.version(), legal.effectiveDate(),
                legal.operatorName(), legal.operatorAddress(), legal.contactEmail(), legal.grievanceOfficerName(),
                legal.grievanceOfficerEmail(), legal.jurisdiction()));
    }

    /** @param emailEnabled false while outgoing email is not configured (password resets can't be emailed) */
    record PublicConfig(boolean registrationOpen, boolean emailEnabled, Legal legal) {
    }

    /** Operator details for the Terms of Service, Privacy Notice and grievance page; blank when not configured. */
    record Legal(String version, String effectiveDate, String operatorName, String operatorAddress, String contactEmail,
            String grievanceOfficerName, String grievanceOfficerEmail, String jurisdiction) {
    }
}
