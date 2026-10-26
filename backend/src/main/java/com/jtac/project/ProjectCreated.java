package com.jtac.project;

import java.util.UUID;

/**
 * Published synchronously inside the creating transaction so dependent modules (workflow)
 * can provision project defaults atomically.
 *
 * @param template {@code KANBAN} (open workflow) or {@code STRUCTURED} (guided workflow)
 */
public record ProjectCreated(UUID organizationId, UUID projectId, String template, UUID actorId) {
}
