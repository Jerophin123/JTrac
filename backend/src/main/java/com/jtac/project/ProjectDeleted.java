package com.jtac.project;

import java.util.UUID;

/** A project and all its data were permanently deleted (published after commit). */
public record ProjectDeleted(UUID organizationId, UUID projectId, String key) {
}
