/**
 * Audit trail of security-relevant actions (sign-in, membership and role changes,
 * permission and workflow changes, token and webhook management, settings).
 * Public API: {@link com.jtac.audit.AuditLog}, {@link com.jtac.audit.AuditEntry},
 * {@link com.jtac.audit.AuditRecord}. Records are append-only for the application role.
 */
@ApplicationModule(displayName = "Audit")
package com.jtac.audit;

import org.springframework.modulith.ApplicationModule;
