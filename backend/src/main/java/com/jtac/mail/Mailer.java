package com.jtac.mail;

/** Queues email for reliable background delivery. Joins the caller's transaction. */
public interface Mailer {

    void send(EmailMessage message);

    /** False in email-pending mode: messages are queued but not sent until SMTP is configured. */
    boolean deliveryEnabled();

    /** Absolute URL for a path in the web application, e.g. {@code /reset-password?token=...}. */
    String link(String pathAndQuery);
}
