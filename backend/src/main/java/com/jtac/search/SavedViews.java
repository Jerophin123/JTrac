package com.jtac.search;

import com.jtac.issue.IssueFilter;
import com.jtac.organization.OrgContext;
import java.util.List;
import java.util.UUID;

/**
 * Saved views the caller may see (their own, and shared views of projects they can browse).
 * Running a view's query always re-applies the caller's own permissions.
 */
public interface SavedViews {

    List<View> list(OrgContext org, String projectKey);

    View get(OrgContext org, UUID id);

    record View(UUID id, String name, String kind, String projectKey, IssueFilter query, boolean shared, String ownerName) {
    }
}
