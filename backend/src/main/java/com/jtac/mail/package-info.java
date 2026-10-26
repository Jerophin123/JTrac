/**
 * Transactional email. {@link com.jtac.mail.Mailer#send} writes to the outbox inside the
 * caller's transaction; a background dispatcher delivers with retries and exponential
 * backoff. Transports: {@code smtp} (production) and {@code file} (development: writes
 * {@code .eml} files to a local directory).
 */
@ApplicationModule(displayName = "Mail")
package com.jtac.mail;

import org.springframework.modulith.ApplicationModule;
