package com.jtac.project;

import java.util.UUID;

public record ProjectRef(UUID id, UUID organizationId, String key, String name, String visibility, boolean archived) {
}
