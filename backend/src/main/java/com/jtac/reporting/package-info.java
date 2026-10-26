/**
 * Dashboards and their widgets (breakdowns, counts, created-vs-resolved trends, milestone
 * progress, issue lists). Widget data is always computed for the viewer through
 * {@link com.jtac.issue.IssueSearch}, so a shared dashboard never shows a viewer issues or
 * field values they could not see themselves.
 */
@ApplicationModule(displayName = "Reporting")
package com.jtac.reporting;

import org.springframework.modulith.ApplicationModule;
