package com.jtac.organization;

/**
 * Organization roles. Owners and admins administer the organization and are implicit
 * administrators of every project; members see organization-visible projects; guests only
 * see projects they were explicitly added to.
 */
public enum OrgRole {
    OWNER, ADMIN, MEMBER, GUEST;

    public boolean isAdmin() {
        return this == OWNER || this == ADMIN;
    }
}
