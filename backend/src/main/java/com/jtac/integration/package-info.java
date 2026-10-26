/**
 * Integrations: outgoing webhooks (HMAC-signed, retried with backoff, with delivery logs)
 * and CSV import/export of issues.
 *
 * <p>Webhook payloads are minimal and permission-filtered: an event is delivered only if
 * the webhook's creator can still browse the issue, and changed-field names are limited to
 * fields visible to the creator. Webhook secrets are encrypted at rest (AES-GCM).
 */
@ApplicationModule(displayName = "Integrations")
package com.jtac.integration;

import org.springframework.modulith.ApplicationModule;
