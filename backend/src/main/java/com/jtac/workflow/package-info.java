/**
 * Configurable workflows: statuses (with categories), versioned workflow definitions
 * (draft → published), role-restricted transitions and field rules (hidden, read-only,
 * optional, required) per status and role. Publishing a version that removes statuses
 * requires an explicit mapping; the issue module migrates affected issues in the same
 * transaction ({@link com.jtac.workflow.WorkflowPublished}).
 *
 * <p>Public API: {@link com.jtac.workflow.WorkflowCatalog}, {@link com.jtac.workflow.Workflow},
 * {@link com.jtac.workflow.StatusUsage} (SPI implemented by the issue module).
 */
@ApplicationModule(displayName = "Workflow")
package com.jtac.workflow;

import org.springframework.modulith.ApplicationModule;
