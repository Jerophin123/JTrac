package com.jtac.issue;

import com.jtac.project.ProjectContext;

/** Issue writes for other modules (e.g. CSV import), with the same checks as the REST API. */
public interface IssueCommands {

    /** Creates an issue as the context's actor; requires CREATE_ISSUES. */
    IssueHandle create(ProjectContext project, NewIssue input);
}
