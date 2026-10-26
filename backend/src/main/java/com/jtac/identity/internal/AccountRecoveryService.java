package com.jtac.identity.internal;

import com.jtac.audit.AuditEntry;
import com.jtac.audit.AuditLog;
import com.jtac.identity.Consent;
import com.jtac.mail.EmailTemplate;
import com.jtac.mail.Mailer;
import com.jtac.shared.web.ApiErrors;
import com.jtac.shared.web.RateLimiter;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriUtils;

/**
 * Password reset, self-registration and email verification.
 *
 * <p>Requests never reveal whether an email address has an account: they always succeed
 * from the caller's point of view, and only the mailbox owner learns the outcome.
 */
@Service
class AccountRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(AccountRecoveryService.class);
    static final Duration RESET_TTL = Duration.ofHours(1);
    static final Duration VERIFICATION_TTL = Duration.ofDays(2);

    private final UserAccountService accounts;
    private final AccountTokenService tokens;
    private final Mailer mailer;
    private final AuditLog audit;
    private final SessionTerminator sessions;
    private final boolean registrationOpen;
    private final RateLimiter perEmail = new RateLimiter("account email", 3, Duration.ofHours(1), 50_000);
    private final RateLimiter perClient = new RateLimiter("account", 20, Duration.ofHours(1), 50_000);

    AccountRecoveryService(UserAccountService accounts, AccountTokenService tokens, Mailer mailer, AuditLog audit,
            SessionTerminator sessions, @Value("${jtac.registration.mode:INVITE_ONLY}") String registrationMode) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.mailer = mailer;
        this.audit = audit;
        this.sessions = sessions;
        this.registrationOpen = "OPEN".equalsIgnoreCase(registrationMode);
    }

    boolean registrationOpen() {
        return registrationOpen;
    }

    @Transactional
    void requestPasswordReset(String email, String clientAddress) {
        perClient.acquireOrThrow(clientAddress);
        String address = accounts.requireValidEmail("email", email);
        if (perEmail.tryAcquire(UserAccountService.throttleKey(address)).isPresent()) {
            return; // silently drop: never reveal throttling per address
        }
        accounts.findAccountByEmail(address).filter(UserAccount::isActive).ifPresent(account -> {
            String token = tokens.issue(account.id(), AccountTokenService.Purpose.PASSWORD_RESET, RESET_TTL);
            mailer.send(EmailTemplate.subject("Reset your JTRAC password")
                    .paragraph("Hi " + account.displayName() + ",")
                    .paragraph("Someone asked to reset the password for your JTRAC account. The link is valid for one hour and can be used once.")
                    .action("Choose a new password", mailer.link("/reset-password?token=" + UriUtils.encode(token, "UTF-8")))
                    .footnote("If you did not ask for this, ignore this email. Your password stays the same.")
                    .to(account.email()));
            audit.record(AuditEntry.of(null, account.id(), "account.password_reset_requested").target("user", account.id()));
        });
    }

    @Transactional
    void completePasswordReset(String token, String newPassword) {
        UserAccountService.requireAcceptablePassword("password", newPassword);
        UUID userId = tokens.consume(token, AccountTokenService.Purpose.PASSWORD_RESET);
        UserAccount account = accounts.resetPassword(userId, newPassword);
        int ended = sessions.endSessions(account.email(), null);
        audit.record(AuditEntry.of(null, userId, "account.password_reset").target("user", userId)
                .detail("sessionsEnded", ended));
        log.info("Password reset completed userId={} sessionsEnded={}", userId, ended);
    }

    @Transactional
    void register(String email, String displayName, String password, Boolean acceptTerms, String clientAddress) {
        if (!registrationOpen) {
            throw ApiErrors.forbidden("Self-registration is disabled. Ask an administrator for an invitation.");
        }
        Consent.require(acceptTerms);
        perClient.acquireOrThrow(clientAddress);
        String address = accounts.requireValidEmail("email", email);
        UserAccountService.requireDisplayName("displayName", displayName);
        UserAccountService.requireAcceptablePassword("password", password);
        if (perEmail.tryAcquire(UserAccountService.throttleKey(address)).isPresent()) {
            return;
        }
        var existing = accounts.findAccountByEmail(address);
        if (existing.isPresent()) {
            mailer.send(EmailTemplate.subject("You already have a JTRAC account")
                    .paragraph("Someone tried to create a JTRAC account with this email address, but it already has one.")
                    .action("Sign in", mailer.link("/login"))
                    .paragraph("Forgot your password? You can reset it from the sign-in page.")
                    .to(existing.get().email()));
            return;
        }
        UUID id = accounts.create(address, displayName, password, false);
        accounts.recordConsent(id);
        sendVerification(id, address, displayName.strip());
        audit.record(AuditEntry.of(null, id, "account.registered").target("user", id));
    }

    @Transactional
    void resendVerification(String email, String clientAddress) {
        perClient.acquireOrThrow(clientAddress);
        String address = accounts.requireValidEmail("email", email);
        if (perEmail.tryAcquire(UserAccountService.throttleKey(address)).isPresent()) {
            return;
        }
        accounts.findAccountByEmail(address)
                .filter(account -> account.isActive() && !account.isVerified())
                .ifPresent(account -> sendVerification(account.id(), account.email(), account.displayName()));
    }

    private void sendVerification(UUID userId, String email, String displayName) {
        String token = tokens.issue(userId, AccountTokenService.Purpose.EMAIL_VERIFICATION, VERIFICATION_TTL);
        mailer.send(EmailTemplate.subject("Confirm your email address")
                .paragraph("Hi " + displayName + ",")
                .paragraph("Confirm your email address to finish creating your JTRAC account.")
                .action("Confirm email address", mailer.link("/verify-email?token=" + UriUtils.encode(token, "UTF-8")))
                .footnote("The link is valid for 48 hours. If you did not sign up for JTRAC, ignore this email.")
                .to(email));
    }

    @Transactional
    void verifyEmail(String token) {
        UUID userId = tokens.consume(token, AccountTokenService.Purpose.EMAIL_VERIFICATION);
        accounts.markVerified(userId);
        audit.record(AuditEntry.of(null, userId, "account.email_verified").target("user", userId));
    }
}
