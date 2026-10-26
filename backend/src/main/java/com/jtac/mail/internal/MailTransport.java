package com.jtac.mail.internal;

import com.jtac.mail.EmailMessage;

interface MailTransport {

    /** Delivers the message with the settings in force, or throws. */
    void deliver(java.util.UUID outboxId, EmailMessage message) throws Exception;
}
