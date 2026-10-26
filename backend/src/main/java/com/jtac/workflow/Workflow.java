package com.jtac.workflow;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The published workflow of a project with evaluation logic. Immutable.
 */
public record Workflow(UUID versionId, int versionNo, WorkflowDefinition definition, Map<UUID, Status> statusesById) {

    /** Fields that field rules may restrict. The title is always visible and editable. */
    public static final Set<String> SYSTEM_FIELDS = Set.of("description", "assignee", "priority", "labels", "dueDate",
            "startDate", "estimate", "milestone", "parent");

    public UUID initialStatusId() {
        return definition.initialStatusId();
    }

    public Optional<Status> status(UUID id) {
        return Optional.ofNullable(statusesById.get(id));
    }

    public List<Status> boardStatuses() {
        return definition.statuses().stream().map(statusesById::get).filter(java.util.Objects::nonNull).toList();
    }

    /** Transitions the given roles may use from {@code from} (excluding no-op transitions). */
    public List<WorkflowDefinition.Transition> transitionsFrom(UUID from, Collection<UUID> roles) {
        return definition.transitions().stream()
                .filter(t -> !t.to().equals(from))
                .filter(t -> t.from().isEmpty() || t.from().contains(from))
                .filter(t -> t.roles().isEmpty() || t.roles().stream().anyMatch(roles::contains))
                .toList();
    }

    public boolean allows(UUID from, UUID to, Collection<UUID> roles) {
        return transitionsFrom(from, roles).stream().anyMatch(t -> t.to().equals(to));
    }

    /**
     * Effective access to {@code field} in {@code statusId} for a user holding {@code roles}:
     * per role the most specific rule applies; across roles the most permissive visibility
     * wins, and an editable field is REQUIRED if any role that can edit it requires it.
     */
    public FieldAccess access(String field, UUID statusId, Collection<UUID> roles) {
        if (roles.isEmpty()) {
            return FieldAccess.HIDDEN;
        }
        FieldAccess best = FieldAccess.HIDDEN;
        boolean required = false;
        for (UUID role : roles) {
            FieldAccess access = forRole(field, statusId, role);
            if (rank(access) > rank(best)) {
                best = access;
            }
            if (access == FieldAccess.REQUIRED) {
                required = true;
            }
        }
        if (best.editable()) {
            return required ? FieldAccess.REQUIRED : FieldAccess.OPTIONAL;
        }
        return best;
    }

    private FieldAccess forRole(String field, UUID statusId, UUID role) {
        FieldAccess statusAndRole = null;
        FieldAccess statusOnly = null;
        FieldAccess roleOnly = null;
        FieldAccess any = null;
        for (WorkflowDefinition.Rule rule : definition.rules()) {
            if (!rule.field().equals(field)) {
                continue;
            }
            boolean statusMatch = rule.statusId() != null && rule.statusId().equals(statusId);
            boolean roleMatch = rule.roleId() != null && rule.roleId().equals(role);
            if (statusMatch && roleMatch) {
                statusAndRole = rule.access();
            } else if (statusMatch && rule.roleId() == null) {
                statusOnly = rule.access();
            } else if (roleMatch && rule.statusId() == null) {
                roleOnly = rule.access();
            } else if (rule.statusId() == null && rule.roleId() == null) {
                any = rule.access();
            }
        }
        if (statusAndRole != null) {
            return statusAndRole;
        }
        if (statusOnly != null) {
            return statusOnly;
        }
        if (roleOnly != null) {
            return roleOnly;
        }
        return any != null ? any : FieldAccess.OPTIONAL;
    }

    private static int rank(FieldAccess access) {
        return switch (access) {
            case HIDDEN -> 0;
            case READ_ONLY -> 1;
            case REQUIRED, OPTIONAL -> 2;
        };
    }
}
