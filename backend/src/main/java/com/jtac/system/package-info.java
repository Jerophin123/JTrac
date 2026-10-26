/**
 * Server administration: settings that apply to the whole installation (outgoing email,
 * server information). Only server administrators may use it; checks are made in the
 * service layer, never only in the UI.
 */
@ApplicationModule(displayName = "System administration")
package com.jtac.system;

import org.springframework.modulith.ApplicationModule;
