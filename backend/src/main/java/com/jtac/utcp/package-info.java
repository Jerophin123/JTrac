/**
 * AI agent connections over the Universal Tool Calling Protocol (UTCP) (Experimental).
 *
 * <p>Exposes a native UTCP discovery manual and tool calling endpoints over HTTP,
 * enabling UTCP-compatible AI agents to directly discover and invoke JTRAC read-only tools.
 */
@ApplicationModule(displayName = "AI agent connections (UTCP)")
package com.jtac.utcp;

import org.springframework.modulith.ApplicationModule;

