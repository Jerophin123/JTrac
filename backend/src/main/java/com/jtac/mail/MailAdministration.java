package com.jtac.mail;

import com.jtrac.serverconfig.Problem;
import com.jtrac.serverconfig.SmtpSettings;
import com.jtrac.serverconfig.SmtpTestResult;
import java.util.List;

/**
 * Outgoing email administration. Authorization is the caller's job: only server
 * administrators may reach these operations (see the system module).
 */
public interface MailAdministration {

    /** Current settings (never the password), where they come from, and outbox health. */
    MailStatus status();

    /**
     * Validates, saves to the configuration files and applies new SMTP settings immediately.
     *
     * @param password new SMTP password; null keeps the stored one
     * @return validation problems; nothing is saved when any is an error
     */
    List<Problem> update(SmtpSettings settings, String password);

    /**
     * Sends a test email with these settings without saving them.
     *
     * @param password password to test with; null uses the stored one
     */
    SmtpTestResult test(SmtpSettings settings, String password, String recipient);

    /**
     * @param settings      current settings
     * @param passwordSet   whether an SMTP password is stored
     * @param overriddenBy  settings fixed by environment variables or system properties, which
     *                      take precedence over saved values
     * @param configDirectory where settings are saved
     */
    record MailStatus(SmtpSettings settings, boolean passwordSet, List<String> overriddenBy, String configDirectory,
            Outbox outbox) {
    }

    /** Queue health: what's waiting, what failed, and the last error (secrets removed). */
    record Outbox(long pending, long failed, long sentLast24Hours, String lastError) {
    }
}
