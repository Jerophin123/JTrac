package com.jtac.system.internal;

import com.jtac.audit.AuditEntry;
import com.jtac.audit.AuditLog;
import com.jtac.identity.CurrentUser;
import com.jtac.identity.UserDirectory;
import com.jtac.mail.MailAdministration;
import com.jtac.shared.web.ApiErrors;
import com.jtac.shared.web.RateLimiter;
import com.jtrac.serverconfig.Problem;
import com.jtrac.serverconfig.SmtpSettings;
import com.jtrac.serverconfig.SmtpTestResult;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Service;

/** Authorization and auditing for server administration; the mail module does the work. */
@Service
class SystemService {

    private final UserDirectory users;
    private final MailAdministration mail;
    private final AuditLog audit;
    /** Test emails are real emails: cap them so an account can't be used to send spam. */
    private final RateLimiter testEmails = new RateLimiter("test email", 10, Duration.ofHours(1), 1_000);

    SystemService(UserDirectory users, MailAdministration mail, AuditLog audit) {
        this.users = users;
        this.mail = mail;
        this.audit = audit;
    }

    MailAdministration.MailStatus mailStatus(CurrentUser user) {
        requireServerAdmin(user);
        return mail.status();
    }

    List<Problem> updateMail(CurrentUser user, SmtpSettings settings, String password) {
        requireServerAdmin(user);
        List<Problem> problems = mail.update(settings, password);
        if (problems.stream().noneMatch(Problem::isError)) {
            audit.record(AuditEntry.of(null, user.id(), "system.mail_updated").target("system", "mail")
                    .detail("enabled", settings.enabled()).detail("host", settings.host()).detail("port", settings.port())
                    .detail("tls", settings.tls().name()).detail("passwordChanged", password != null));
        }
        return problems;
    }

    SmtpTestResult testMail(CurrentUser user, SmtpSettings settings, String password, String recipient) {
        requireServerAdmin(user);
        testEmails.acquireOrThrow(user.id().toString());
        SmtpTestResult result = mail.test(settings, password, recipient);
        audit.record(AuditEntry.of(null, user.id(), "system.mail_tested").target("system", "mail")
                .detail("host", settings.host()).detail("accepted", result.accepted()));
        return result;
    }

    void requireServerAdmin(CurrentUser user) {
        if (user.viaApiToken()) {
            throw ApiErrors.forbidden("Server settings can only be used from a signed-in session.");
        }
        if (!users.isServerAdministrator(user.id())) {
            throw ApiErrors.forbidden("Only a server administrator can view or change server settings.");
        }
    }
}
