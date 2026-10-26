package com.jtac.realtime;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Pushes small notifications to connected clients of an organization. */
public interface RealtimeHub {

    /** Sends {@code type} with {@code data} to the given users' open connections in the organization. */
    void send(UUID organizationId, Collection<UUID> userIds, String type, Map<String, Object> data);
}
