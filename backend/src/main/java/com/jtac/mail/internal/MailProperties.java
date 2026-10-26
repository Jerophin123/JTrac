package com.jtac.mail.internal;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Initial mail settings, from the configuration files written by the setup wizard (or the
 * environment in development). Administrators can change the SMTP part at runtime; see
 * {@link MailRuntime}.
 *
 * @param transport     {@code smtp}, {@code disabled} (email pending: queued, not sent) or
 *                      {@code file} (development: writes .eml files)
 * @param from          sender address (a legacy "Name &lt;address&gt;" value is accepted)
 * @param fromName      sender display name
 * @param replyTo       optional reply-to address
 * @param fileDirectory target directory for the {@code file} transport
 * @param maxAttempts   attempts before a message is marked FAILED
 * @param batchSize     messages claimed per dispatch run
 */
@ConfigurationProperties("jtac.mail")
record MailProperties(
        @DefaultValue("file") String transport,
        @DefaultValue("no-reply@jtrac.local") String from,
        @DefaultValue("JTRAC") String fromName,
        @DefaultValue("") String replyTo,
        @DefaultValue("./data/mail") Path fileDirectory,
        @DefaultValue("8") int maxAttempts,
        @DefaultValue("20") int batchSize,
        @DefaultValue Smtp smtp) {

    /**
     * @param tls                  STARTTLS, IMPLICIT or NONE; blank = derived from the port
     * @param trustedCertificates  optional PEM file with extra CA certificates
     */
    record Smtp(
            @DefaultValue("") String host,
            @DefaultValue("587") int port,
            @DefaultValue("true") boolean auth,
            @DefaultValue("") String username,
            @DefaultValue("") String password,
            @DefaultValue("") String tls,
            @DefaultValue("15") int timeoutSeconds,
            @DefaultValue("") String trustedCertificates) {

        @Override
        public String toString() {
            return "Smtp[host=" + host + ", port=" + port + ", auth=" + auth + ", username=" + username + ", password=****, tls="
                    + tls + "]";
        }
    }
}
