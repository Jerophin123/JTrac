/**
 * Shared kernel and cross-cutting infrastructure: identifiers, API problem responses,
 * request correlation, database migration settings and static SPA delivery.
 *
 * <p>This module is {@code OPEN}: every other module may use its types. It must never
 * depend on a business module, and it must not accumulate business logic.
 */
@ApplicationModule(displayName = "Shared infrastructure", type = ApplicationModule.Type.OPEN)
package com.jtac.shared;

import org.springframework.modulith.ApplicationModule;
