package com.jtac.issue;

import com.jtac.organization.OrgContext;
import java.util.List;

/**
 * Issue reads for other modules, with exactly the checks of the REST API: an issue the
 * caller cannot browse is reported as not found, and field rules are applied for the caller.
 */
public interface IssueDetails {

    IssueView view(OrgContext org, String issueKey);

    List<IssueActivityEntry> activity(OrgContext org, String issueKey);
}
