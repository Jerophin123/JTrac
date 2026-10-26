package com.jtac.workflow;

import java.util.UUID;

public record Status(UUID id, String name, StatusCategory category, int position, boolean retired) {
}
