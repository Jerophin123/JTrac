package com.jtac.identity.internal;

import com.jtac.audit.AuditEntry;
import com.jtac.audit.AuditLog;
import com.jtac.identity.CurrentUser;
import com.jtac.mail.EmailTemplate;
import com.jtac.mail.Mailer;
import com.jtac.shared.Ids;
import com.jtac.shared.web.ApiErrors;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consent to the Terms of Service and Privacy Notice (DPDP Act, 2023, sections 5 to 7):
 * recorded per version, required again when the version changes, and withdrawable as easily
 * as it was given. Withdrawing also files an erasure request with the grievance officer.
 */
@Service
class ConsentService {

    record ConsentRecord(String version, Instant givenAt, Instant withdrawnAt) {
    }

    private final JdbcClient jdbc;
    private final LegalProperties legal;
    private final UserAccountRepository accounts;
    private final Mailer mailer;
    private final AuditLog audit;

    ConsentService(JdbcClient jdbc, LegalProperties legal, UserAccountRepository accounts, Mailer mailer, AuditLog audit) {
        this.jdbc = jdbc;
        this.legal = legal;
        this.accounts = accounts;
        this.mailer = mailer;
        this.audit = audit;
    }

    String currentVersion() {
        return legal.version();
    }

    /** Records acceptance of the current version (idempotent while an active acceptance exists). */
    @Transactional
    void record(UUID userId) {
        if (hasCurrentConsent(userId)) {
            return;
        }
        jdbc.sql("INSERT INTO user_consent (id, user_id, version) VALUES (:id, :user, :version)")
                .param("id", Ids.newId()).param("user", userId).param("version", legal.version()).update();
        audit.record(AuditEntry.of(null, userId, "account.consent_given").target("user", userId)
                .detail("version", legal.version()));
    }

    /** Accepting or withdrawing is a personal decision, so it needs a signed-in session, not an API token. */
    @Transactional
    void accept(CurrentUser user) {
        requireSession(user);
        record(user.id());
    }

    private static void requireSession(CurrentUser user) {
        if (user.viaApiToken()) {
            throw ApiErrors.forbidden("Accept or withdraw consent from a signed-in session.");
        }
    }

    /** Whether the user has accepted the version in force and not withdrawn it. */
    @Transactional(readOnly = true)
    boolean hasCurrentConsent(UUID userId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM user_consent
                                WHERE user_id = :user AND version = :version AND withdrawn_at IS NULL)
                """)
                .param("user", userId).param("version", legal.version())
                .query(Boolean.class).single();
    }

    @Transactional(readOnly = true)
    List<ConsentRecord> history(UUID userId) {
        return jdbc.sql("SELECT version, given_at, withdrawn_at FROM user_consent WHERE user_id = :user ORDER BY given_at DESC")
                .param("user", userId)
                .query((rs, n) -> new ConsentRecord(rs.getString("version"),
                        rs.getObject("given_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("withdrawn_at", OffsetDateTime.class) == null ? null
                                : rs.getObject("withdrawn_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /**
     * Withdraws every active consent and asks the grievance officer to erase the account. The
     * user keeps their session but must accept the terms again before using JTRAC.
     */
    @Transactional
    void withdraw(CurrentUser user, String reason) {
        requireSession(user);
        UUID userId = user.id();
        int withdrawn = jdbc.sql("UPDATE user_consent SET withdrawn_at = now() WHERE user_id = :user AND withdrawn_at IS NULL")
                .param("user", userId).update();
        UserAccount account = accounts.findById(userId).orElseThrow();
        String note = reason == null || reason.isBlank() ? "(no reason given)" : reason.strip();
        audit.record(AuditEntry.of(null, userId, "account.consent_withdrawn").target("user", userId)
                .detail("consents", withdrawn));

        if (!legal.grievanceOfficerEmail().isBlank()) {
            mailer.send(EmailTemplate.subject("Erasure request from " + account.displayName())
                    .paragraph(account.displayName() + " (" + account.email() + ", @" + account.handle()
                            + ") withdrew consent to the JTRAC Terms of Service and Privacy Notice and asked for their "
                            + "account and personal data to be erased.")
                    .paragraph("Reason: " + note)
                    .paragraph("Under the Digital Personal Data Protection Act, 2023, erase the personal data unless "
                            + "a law requires keeping it, and reply to the person within the published timeline.")
                    .to(legal.grievanceOfficerEmail()));
        }
        mailer.send(EmailTemplate.subject("We received your erasure request")
                .paragraph("Hi " + account.displayName() + ",")
                .paragraph("You withdrew your consent to the JTRAC Terms of Service and Privacy Notice and asked for "
                        + "your account to be erased. The operator of this JTRAC installation will process the request "
                        + "and reply to this address.")
                .paragraph("Changed your mind? Sign in and accept the terms again; nothing has been deleted yet.")
                .footnote(legal.grievanceOfficerEmail().isBlank()
                        ? "Questions: contact the administrator of your JTRAC installation."
                        : "Questions: write to " + legal.grievanceOfficerEmail() + ".")
                .to(account.email()));
    }
}
