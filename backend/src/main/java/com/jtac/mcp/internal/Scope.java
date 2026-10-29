package com.jtac.mcp.internal;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What a connection lets an agent do. Scopes only narrow: every call is still checked
 * against the user's current project permissions and field rules, so a scope never grants
 * anything the user could not do in the web app.
 */
enum Scope {
    ISSUES_READ("issues:read", false, "Search and read issues, comments, history, saved filters and workflow options"),
    ATTACHMENTS_READ("attachments:read", false, "Read issue attachments (text, logs and images)"),
    PROJECTS_READ("projects:read", false, "Read project details, workflows, members who can be assigned, and statistics"),
    NOTIFICATIONS_READ("notifications:read", false, "Read your own notifications and notification preferences"),
    REPORTS_READ("reports:read", false, "Read fix reports and verification records on issues"),
    ISSUES_COMMENT("issues:comment", true, "Add comments as you"),
    ISSUES_WRITE("issues:write", true, "Change issue fields (title, description, priority, assignee, labels, dates) and links"),
    ISSUES_TRANSITION("issues:transition", true, "Move issues through workflow transitions"),
    REPORTS_WRITE("reports:write", true, "Record fix reports and verification results");

    private final String wire;
    private final boolean write;
    private final String description;

    Scope(String wire, boolean write, String description) {
        this.wire = wire;
        this.write = write;
        this.description = description;
    }

    String wire() {
        return wire;
    }

    boolean write() {
        return write;
    }

    String description() {
        return description;
    }

    static Optional<Scope> of(String wire) {
        return Arrays.stream(values()).filter(s -> s.wire.equals(wire)).findFirst();
    }

    /** Known scopes among {@code wires}; unknown values are ignored (OAuth scope semantics). */
    static Set<Scope> parse(Collection<String> wires) {
        Set<Scope> result = EnumSet.noneOf(Scope.class);
        if (wires != null) {
            wires.forEach(w -> of(w).ifPresent(result::add));
        }
        return result;
    }

    /** Space-separated OAuth {@code scope} parameter. */
    static Set<Scope> parseParameter(String parameter) {
        return parameter == null || parameter.isBlank() ? EnumSet.noneOf(Scope.class)
                : parse(List.of(parameter.strip().split("\\s+")));
    }

    static List<String> wires(Collection<Scope> scopes) {
        return scopes.stream().sorted().map(Scope::wire).toList();
    }

    static String parameter(Collection<Scope> scopes) {
        return String.join(" ", wires(scopes));
    }

    /** Preset permission sets offered when creating a connection. All are read-only except the contributor. */
    enum Profile {
        ISSUE_READER("Issue reader", EnumSet.of(ISSUES_READ, ATTACHMENTS_READ)),
        PROJECT_ANALYST("Project analyst", EnumSet.of(ISSUES_READ, ATTACHMENTS_READ, PROJECTS_READ)),
        CODING_ASSISTANT("Coding assistant", EnumSet.of(ISSUES_READ, ATTACHMENTS_READ, PROJECTS_READ, REPORTS_READ)),
        ISSUE_CONTRIBUTOR("Issue contributor", EnumSet.of(ISSUES_READ, ATTACHMENTS_READ, PROJECTS_READ, REPORTS_READ,
                ISSUES_COMMENT, ISSUES_WRITE, ISSUES_TRANSITION, REPORTS_WRITE));

        /** New connections are read-only unless the user chooses otherwise. */
        static final Profile DEFAULT = CODING_ASSISTANT;

        private final String label;
        private final Set<Scope> scopes;

        Profile(String label, Set<Scope> scopes) {
            this.label = label;
            this.scopes = scopes;
        }

        String label() {
            return label;
        }

        Set<Scope> scopes() {
            return EnumSet.copyOf(scopes);
        }

        /** The profile with exactly these scopes, or empty for a custom selection. */
        static Optional<Profile> matching(Collection<Scope> scopes) {
            Set<Scope> set = new LinkedHashSet<>(scopes);
            return Arrays.stream(values()).filter(p -> p.scopes.equals(set)).findFirst();
        }
    }
}
