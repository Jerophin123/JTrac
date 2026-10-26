/**
 * Issues: creation, editing, assignment, workflow transitions, sub-tasks, links, labels,
 * watchers, custom values, ranking (board order), bulk operations, the attributed activity
 * history, and issue search (lists, boards, aggregates).
 *
 * <p>Field rules of the published workflow are enforced here on every read and write:
 * hidden fields are removed from responses, search filters, sorting, aggregates and
 * activity; read-only fields cannot be changed; required fields must be filled.
 *
 * <p>Public API: {@link com.jtac.issue.IssueAccess}, {@link com.jtac.issue.IssueSearch},
 * {@link com.jtac.issue.IssueChanged} (domain event), and their value types.
 */
@ApplicationModule(displayName = "Issues")
package com.jtac.issue;

import org.springframework.modulith.ApplicationModule;
