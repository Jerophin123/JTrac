/**
 * Identity: user accounts, credentials, authentication, sessions and the HTTP security
 * configuration. Organizations, memberships and authorization policies are separate
 * modules (introduced in later milestones) that depend on this one, never the reverse.
 *
 * <p>Public API: {@link com.jtac.identity.CurrentUser}. Everything under
 * {@code internal} is private to this module.
 */
@ApplicationModule(displayName = "Identity")
package com.jtac.identity;

import org.springframework.modulith.ApplicationModule;
