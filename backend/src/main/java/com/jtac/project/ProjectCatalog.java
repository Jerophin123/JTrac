package com.jtac.project;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read access to a project's issue types, labels, milestones and roles (no authorization). */
public interface ProjectCatalog {

    record IssueType(UUID id, String name, String icon, String color, boolean subtask, int position, boolean archived) {
    }

    record Label(UUID id, String name, String color) {
    }

    record Milestone(UUID id, String name, String description, LocalDate startDate, LocalDate dueDate, String state,
            long version) {
    }

    record Role(UUID id, String name, List<ProjectPermission> permissions, String systemKey, int position) {
    }

    List<IssueType> issueTypes(UUID projectId);

    List<Label> labels(UUID projectId);

    List<Milestone> milestones(UUID projectId);

    List<Role> roles(UUID projectId);

    Optional<UUID> adminRoleId(UUID projectId);

    /** The project's description (Markdown), if it has one. */
    Optional<String> description(UUID projectId);
}
