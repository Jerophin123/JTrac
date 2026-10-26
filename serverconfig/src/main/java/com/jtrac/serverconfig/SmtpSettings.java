package com.jtrac.serverconfig;

/**
 * Outgoing email (SMTP) settings, without the password (kept in {@link Secrets}).
 *
 * @param enabled              false = email-pending mode: messages are queued, not sent
 * @param host                 SMTP server host name or IP address
 * @param port                 usually 587 (STARTTLS), 465 (implicit TLS) or 25 (relay)
 * @param auth                 sign in with {@code username} and the stored password
 * @param username             SMTP user name (often the mailbox address)
 * @param tls                  how the connection is encrypted
 * @param fromAddress          envelope and header sender address
 * @param fromName             optional sender display name
 * @param replyTo              optional reply-to address
 * @param timeoutSeconds       connect, read and write timeout
 * @param maxAttempts          delivery attempts before a message is marked failed
 * @param trustedCertificates  optional PEM file with extra CA certificates (private relays);
 *                             certificates are always verified
 */
public record SmtpSettings(boolean enabled, String host, int port, boolean auth, String username, TlsMode tls,
        String fromAddress, String fromName, String replyTo, int timeoutSeconds, int maxAttempts,
        String trustedCertificates) {

    public static final int DEFAULT_TIMEOUT_SECONDS = 15;
    public static final int DEFAULT_MAX_ATTEMPTS = 8;

    /** Email-pending mode: nothing configured yet, messages wait in the outbox. */
    public static SmtpSettings disabled() {
        return new SmtpSettings(false, "", 587, true, "", TlsMode.STARTTLS, "", "JTRAC", "", DEFAULT_TIMEOUT_SECONDS,
                DEFAULT_MAX_ATTEMPTS, "");
    }

    /** The usual port for a TLS mode: 587 for STARTTLS, 465 for implicit TLS, 25 otherwise. */
    public static int defaultPort(TlsMode tls) {
        return switch (tls) {
            case STARTTLS -> 587;
            case IMPLICIT -> 465;
            case NONE -> 25;
        };
    }

    /** The TLS mode a port usually implies. */
    public static TlsMode defaultTls(int port) {
        return switch (port) {
            case 465 -> TlsMode.IMPLICIT;
            case 25 -> TlsMode.NONE;
            default -> TlsMode.STARTTLS;
        };
    }
}
