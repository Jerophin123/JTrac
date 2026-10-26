/**
 * Planning: the project timeline (scheduled issues, "blocks" dependencies, milestones) and
 * milestone progress. Progress counts done issues against all issues except canceled ones,
 * using the caller's own search permissions.
 */
@ApplicationModule(displayName = "Planning")
package com.jtac.planning;

import org.springframework.modulith.ApplicationModule;
