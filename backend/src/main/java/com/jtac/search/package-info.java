/**
 * Saved views (personal or shared list/board/timeline configurations) and global search
 * across issues, projects and people. Every result is filtered by the caller's access:
 * issues through {@link com.jtac.issue.IssueSearch}, projects through project permissions,
 * people through organization membership.
 */
@ApplicationModule(displayName = "Search")
package com.jtac.search;

import org.springframework.modulith.ApplicationModule;
