package com.jtac.workflow;

import java.util.List;
import java.util.UUID;

public interface WorkflowCatalog {

    /** The project's published workflow (every project has one from creation). */
    Workflow published(UUID projectId);

    /** All statuses of the project, including retired ones, in board order. */
    List<Status> statuses(UUID projectId);
}
