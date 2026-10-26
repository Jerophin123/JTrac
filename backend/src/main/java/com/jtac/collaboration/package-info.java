/**
 * Comments on issues with @mentions (Markdown text; rendered safely by clients without raw
 * HTML). Mentions only resolve to people who can browse the issue; mentioned people start
 * watching it. Comment events feed notifications, webhooks and real-time updates.
 */
@ApplicationModule(displayName = "Collaboration")
package com.jtac.collaboration;

import org.springframework.modulith.ApplicationModule;
