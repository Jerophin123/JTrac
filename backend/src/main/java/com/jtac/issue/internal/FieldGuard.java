package com.jtac.issue.internal;

import com.jtac.field.FieldDefinition;
import com.jtac.workflow.FieldAccess;
import com.jtac.workflow.Workflow;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Effective field access for one viewer, one issue status. Archived custom fields are
 * read-only (still visible if they hold a value).
 */
record FieldGuard(Map<String, FieldAccess> access) {

    static final List<String> SYSTEM_ORDER = List.of("description", "assignee", "priority", "labels", "dueDate",
            "startDate", "estimate", "milestone", "parent");

    static FieldGuard of(Workflow workflow, UUID statusId, Collection<UUID> roles, List<FieldDefinition> fields) {
        Map<String, FieldAccess> access = new LinkedHashMap<>();
        for (String field : SYSTEM_ORDER) {
            access.put(field, workflow.access(field, statusId, roles));
        }
        for (FieldDefinition field : fields) {
            FieldAccess value = workflow.access(field.ruleKey(), statusId, roles);
            if (field.archived() && value.editable()) {
                value = FieldAccess.READ_ONLY;
            }
            access.put(field.ruleKey(), value);
        }
        return new FieldGuard(Map.copyOf(access));
    }

    FieldAccess get(String field) {
        return access.getOrDefault(field, FieldAccess.OPTIONAL);
    }

    boolean visible(String field) {
        return get(field).visible();
    }

    boolean editable(String field) {
        return get(field).editable();
    }

    boolean required(String field) {
        return get(field) == FieldAccess.REQUIRED;
    }
}
