/**
 * Notifications: in-app inbox, email and real-time badge updates, driven by issue events
 * (assignment, mentions, comments, status changes, updates on watched issues).
 *
 * <p>Every notification is re-checked per recipient: people who cannot browse the issue get
 * nothing, and field changes are only mentioned if the field is visible to the recipient.
 * Delivery runs after commit via the event publication registry (retried after crashes).
 */
@ApplicationModule(displayName = "Notifications")
package com.jtac.notification;

import org.springframework.modulith.ApplicationModule;
