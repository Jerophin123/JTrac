package com.jtrac.serverconfig;

/**
 * Secrets the running application needs. Stored apart from ordinary settings in a file
 * readable only by the account that runs JTRAC, and never logged or returned by an API.
 *
 * @param databasePassword      password of the application database role
 * @param smtpPassword          SMTP password or app password (blank when not used)
 * @param integrationSecretKey  base64 AES key that encrypts webhook secrets at rest
 */
public record Secrets(String databasePassword, String smtpPassword, String integrationSecretKey) {

    public static final Secrets EMPTY = new Secrets("", "", "");

    public Secrets withSmtpPassword(String value) {
        return new Secrets(databasePassword, value, integrationSecretKey);
    }

    @Override
    public String toString() {
        return "Secrets[****]";
    }
}
