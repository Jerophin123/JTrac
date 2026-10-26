package com.jtac.mail.internal;

import com.jtac.mail.EmailMessage;
import com.jtrac.serverconfig.SmtpSessions;
import com.jtrac.serverconfig.SmtpSettings;
import jakarta.mail.Address;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Delivers one message with the settings in force: over SMTP (the same session setup as the
 * administrators' test email) or, in development, as an .eml file.
 */
@Component
class MailTransports implements MailTransport {

    private final MailRuntime runtime;

    MailTransports(MailRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public void deliver(UUID outboxId, EmailMessage message) throws Exception {
        MailRuntime.Current current = runtime.current();
        switch (current.transport()) {
            case SMTP -> smtp(current, outboxId, message);
            case FILE -> file(current.fileDirectory(), current.smtp(), outboxId, message);
            case DISABLED -> throw new IllegalStateException("Email delivery is disabled");
        }
    }

    private static void smtp(MailRuntime.Current current, UUID id, EmailMessage message) throws Exception {
        SmtpSettings smtp = current.smtp();
        SmtpSessions.SmtpSession session = SmtpSessions.create(smtp, current.password());
        MimeMessage mime = new MimeMessage(session.session());
        fill(new MimeMessageHelper(mime, message.html() != null, "UTF-8"), smtp, id, message);
        try (Transport transport = session.session().getTransport(session.protocol())) {
            if (smtp.auth()) {
                transport.connect(smtp.host(), smtp.port(), smtp.username(), current.password());
            } else {
                transport.connect();
            }
            transport.sendMessage(mime, mime.getAllRecipients());
        }
    }

    /** Development transport: one RFC 822 .eml file per message, openable in any mail client. */
    private static void file(Path directory, SmtpSettings smtp, UUID id, EmailMessage message) throws Exception {
        Files.createDirectories(directory);
        MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()));
        fill(new MimeMessageHelper(mime, message.html() != null, "UTF-8"), smtp, id, message);
        try (OutputStream out = Files.newOutputStream(directory.resolve(id + ".eml"))) {
            mime.writeTo(out);
        }
    }

    private static void fill(MimeMessageHelper helper, SmtpSettings smtp, UUID id, EmailMessage message) throws Exception {
        helper.setFrom(smtp.fromName() == null || smtp.fromName().isBlank()
                ? new InternetAddress(smtp.fromAddress())
                : new InternetAddress(smtp.fromAddress(), smtp.fromName(), "UTF-8"));
        if (smtp.replyTo() != null && !smtp.replyTo().isBlank()) {
            helper.getMimeMessage().setReplyTo(new Address[] {new InternetAddress(smtp.replyTo())});
        }
        helper.setTo(new InternetAddress(message.to(), true));
        helper.setSubject(message.subject());
        if (message.html() != null) {
            helper.setText(message.text(), message.html());
        } else {
            helper.setText(message.text());
        }
        helper.getMimeMessage().setHeader("X-JTRAC-Message-Id", id.toString());
    }
}
