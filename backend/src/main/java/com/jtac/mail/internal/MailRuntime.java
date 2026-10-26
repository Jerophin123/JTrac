package com.jtac.mail.internal;

import com.jtrac.serverconfig.SmtpSettings;
import com.jtrac.serverconfig.TlsMode;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The mail settings in force. Starts from {@link MailProperties} and is replaced, without a
 * restart, when a server administrator saves new SMTP settings.
 */
@Component
class MailRuntime {

    private static final Logger log = LoggerFactory.getLogger(MailRuntime.class);

    enum Transport { SMTP, DISABLED, FILE }

    /** The current settings; {@code password} never leaves this module. */
    record Current(Transport transport, SmtpSettings smtp, String password, Path fileDirectory, int batchSize) {

        @Override
        public String toString() {
            return "Current[transport=" + transport + ", smtp=" + smtp + ", password=****]";
        }
    }

    private final AtomicReference<Current> current;

    MailRuntime(MailProperties properties) {
        this.current = new AtomicReference<>(initial(properties));
        Current c = current.get();
        if (c.transport() == Transport.DISABLED) {
            log.warn("Outgoing email is not configured: messages are queued until SMTP is set up.");
        } else if (c.transport() == Transport.SMTP) {
            log.info("Outgoing email via {}:{} ({})", c.smtp().host(), c.smtp().port(), c.smtp().tls());
        }
    }

    Current current() {
        return current.get();
    }

    /** Applies new SMTP settings (enabled or email-pending) immediately. */
    void apply(SmtpSettings smtp, String password) {
        current.updateAndGet(c -> new Current(smtp.enabled() ? Transport.SMTP : Transport.DISABLED, smtp, password,
                c.fileDirectory(), c.batchSize()));
        log.info("Email settings changed: {}", smtp.enabled() ? "sending via " + smtp.host() + ":" + smtp.port() : "email pending (disabled)");
    }

    private static Current initial(MailProperties p) {
        Transport transport = switch (p.transport().strip().toLowerCase(Locale.ROOT)) {
            case "smtp" -> Transport.SMTP;
            case "file" -> Transport.FILE;
            case "disabled", "" -> Transport.DISABLED;
            default -> throw new IllegalStateException("Unknown jtac.mail.transport '" + p.transport() + "' (use smtp, disabled or file)");
        };
        String fromAddress = p.from();
        String fromName = p.fromName();
        if (fromAddress.contains("<")) {
            try {
                InternetAddress parsed = new InternetAddress(fromAddress);
                fromAddress = parsed.getAddress();
                if (parsed.getPersonal() != null) {
                    fromName = parsed.getPersonal();
                }
            } catch (AddressException e) {
                throw new IllegalStateException("jtac.mail.from is not a valid address", e);
            }
        }
        MailProperties.Smtp s = p.smtp();
        TlsMode tls = s.tls().isBlank() ? SmtpSettings.defaultTls(s.port()) : TlsMode.valueOf(s.tls().strip().toUpperCase(Locale.ROOT));
        SmtpSettings smtp = new SmtpSettings(transport == Transport.SMTP, s.host(), s.port(), s.auth(), s.username(), tls,
                fromAddress, fromName, p.replyTo(), s.timeoutSeconds(), p.maxAttempts(), s.trustedCertificates());
        return new Current(transport, smtp, s.password(), p.fileDirectory(), p.batchSize());
    }
}
