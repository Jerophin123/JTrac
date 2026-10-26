package com.jtac.mail.internal;

import com.jtac.mail.EmailMessage;
import com.jtac.mail.Mailer;
import com.jtac.shared.Ids;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class OutboxMailer implements Mailer {

    private final JdbcClient jdbc;
    private final MailRuntime runtime;
    private final String publicUrl;

    OutboxMailer(JdbcClient jdbc, MailRuntime runtime, @Value("${jtac.public-url}") String publicUrl) {
        this.jdbc = jdbc;
        this.runtime = runtime;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void send(EmailMessage message) {
        if (message.to() == null || message.to().isBlank() || message.to().contains("\n") || message.to().contains("\r")) {
            throw new IllegalArgumentException("Invalid recipient");
        }
        jdbc.sql("""
                INSERT INTO email_outbox (id, to_address, subject, text_body, html_body)
                VALUES (:id, :to, :subject, :text, :html)
                """)
                .param("id", Ids.newId())
                .param("to", message.to().strip())
                .param("subject", message.subject().replaceAll("[\\r\\n]+", " "))
                .param("text", message.text())
                .param("html", message.html())
                .update();
    }

    @Override
    public boolean deliveryEnabled() {
        return runtime.current().transport() != MailRuntime.Transport.DISABLED;
    }

    @Override
    public String link(String pathAndQuery) {
        return publicUrl + (pathAndQuery.startsWith("/") ? pathAndQuery : "/" + pathAndQuery);
    }
}
