package com.jtac.project;

import java.util.EnumSet;
import java.util.Set;

/** Permissions a project role can grant. */
public enum ProjectPermission {
    /** See the project, its issues, comments, attachments and history. */
    BROWSE,
    CREATE_ISSUES,
    EDIT_ISSUES,
    TRANSITION_ISSUES,
    ASSIGN_ISSUES,
    DELETE_ISSUES,
    COMMENT,
    DELETE_ANY_COMMENT,
    MANAGE_ATTACHMENTS,
    /** Settings, members, roles, workflow, fields, types, labels, milestones, shared views. */
    MANAGE_PROJECT;

    public static final Set<ProjectPermission> ALL = EnumSet.allOf(ProjectPermission.class);
    public static final Set<ProjectPermission> MEMBER = EnumSet.of(BROWSE, CREATE_ISSUES, EDIT_ISSUES,
            TRANSITION_ISSUES, ASSIGN_ISSUES, COMMENT, MANAGE_ATTACHMENTS);
    public static final Set<ProjectPermission> VIEWER = EnumSet.of(BROWSE, COMMENT);
}
