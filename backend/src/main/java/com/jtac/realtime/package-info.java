/**
 * Real-time updates over Server-Sent Events. Messages are small pings (what changed, not
 * the data): clients refetch through the normal, permission-checked API.
 *
 * <p>Limitation: connections are tracked per instance. Running several instances needs a
 * fan-out channel (e.g. PostgreSQL LISTEN/NOTIFY): see docs/operations.md.
 */
@ApplicationModule(displayName = "Real-time")
package com.jtac.realtime;

import org.springframework.modulith.ApplicationModule;
