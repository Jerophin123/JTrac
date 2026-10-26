package com.jtac.organization;

import java.util.List;

/**
 * Organization-level settings and security controls.
 *
 * @param allowedEmailDomains       if non-empty, invitations are limited to these domains
 * @param defaultProjectVisibility  visibility for new projects ({@code ORGANIZATION} or {@code PRIVATE})
 * @param membersCanCreateProjects  whether MEMBER (not only admins) may create projects
 * @param color                     custom brand accent color (e.g. hex #b4532a), customizable by owner
 * @param logoUrl                   custom logo URL or data URI, customizable by owner
 */
public record OrganizationSettings(
        List<String> allowedEmailDomains,
        String defaultProjectVisibility,
        boolean membersCanCreateProjects,
        String color,
        String logoUrl) {

    public static final OrganizationSettings DEFAULTS = new OrganizationSettings(List.of(), "ORGANIZATION", true, null, null);

    public OrganizationSettings(List<String> allowedEmailDomains, String defaultProjectVisibility, boolean membersCanCreateProjects) {
        this(allowedEmailDomains, defaultProjectVisibility, membersCanCreateProjects, null, null);
    }

    public OrganizationSettings {
        allowedEmailDomains = allowedEmailDomains == null ? List.of() : List.copyOf(allowedEmailDomains);
        defaultProjectVisibility = defaultProjectVisibility == null ? "ORGANIZATION" : defaultProjectVisibility;
    }

    public boolean allowsEmail(String email) {
        if (allowedEmailDomains.isEmpty()) {
            return true;
        }
        String domain = email.substring(email.lastIndexOf('@') + 1).toLowerCase(java.util.Locale.ROOT);
        return allowedEmailDomains.stream().anyMatch(d -> d.equalsIgnoreCase(domain));
    }
}
