package com.jtac.workflow;

import java.util.List;
import java.util.UUID;

/**
 * The JSON document of a workflow version.
 *
 * @param initialStatusId status of newly created issues
 * @param statuses        statuses in use, in board order
 * @param transitions     allowed status changes
 * @param rules           field rules; the most specific matching rule per role wins
 *                        (status+role, then status, then role); no rule means OPTIONAL
 */
public record WorkflowDefinition(UUID initialStatusId, List<UUID> statuses, List<Transition> transitions, List<Rule> rules) {

    public WorkflowDefinition {
        statuses = statuses == null ? List.of() : List.copyOf(statuses);
        transitions = transitions == null ? List.of() : List.copyOf(transitions);
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /**
     * @param from  source statuses; empty means "from any status"
     * @param roles project roles allowed to use it; empty means any role with TRANSITION_ISSUES
     */
    public record Transition(String id, String name, List<UUID> from, UUID to, List<UUID> roles) {
        public Transition {
            from = from == null ? List.of() : List.copyOf(from);
            roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }

    /**
     * @param statusId null applies to every status
     * @param roleId   null applies to every role
     * @param field    a system field ({@link Workflow#SYSTEM_FIELDS}) or {@code cf:<key>}
     */
    public record Rule(UUID statusId, UUID roleId, String field, FieldAccess access) {
    }
}
