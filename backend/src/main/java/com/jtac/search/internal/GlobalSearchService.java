package com.jtac.search.internal;

import com.jtac.issue.IssueFilter;
import com.jtac.issue.IssueSearch;
import com.jtac.issue.IssueSummary;
import com.jtac.organization.OrgContext;
import com.jtac.project.ProjectAccess;
import com.jtac.project.ProjectContext;
import com.jtac.shared.web.ApiErrors;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Quick search for the command palette: a few issues, projects and people per query. */
@Service
class GlobalSearchService {

    private static final int LIMIT = 8;

    private final IssueSearch issues;
    private final ProjectAccess projects;
    private final JdbcClient jdbc;

    GlobalSearchService(IssueSearch issues, ProjectAccess projects, JdbcClient jdbc) {
        this.issues = issues;
        this.projects = projects;
        this.jdbc = jdbc;
    }

    record IssueHit(String key, String title, String status, String statusCategory, String projectKey) {
    }

    record ProjectHit(String key, String name, boolean archived) {
    }

    record PersonHit(UUID id, String displayName, String handle) {
    }

    record Results(String query, List<IssueHit> issues, List<ProjectHit> projects, List<PersonHit> people) {
    }

    @Transactional(readOnly = true)
    Results search(OrgContext ctx, String query) {
        String text = query == null ? "" : query.strip();
        if (text.isEmpty()) {
            return new Results(text, List.of(), List.of(), List.of());
        }
        if (text.length() > 200) {
            throw ApiErrors.invalid("q", "At most 200 characters.");
        }
        IssueFilter filter = new IssueFilter(null, text, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, "updated", "desc");
        List<IssueHit> issueHits = issues.search(ctx, filter, 0, LIMIT).items().stream()
                .map(GlobalSearchService::issueHit)
                .toList();
        String needle = text.toLowerCase(Locale.ROOT);
        List<ProjectHit> projectHits = projects.visible(ctx).stream()
                .map(ProjectContext::project)
                .filter(p -> p.key().toLowerCase(Locale.ROOT).startsWith(needle) || p.name().toLowerCase(Locale.ROOT).contains(needle))
                .sorted((a, b) -> Boolean.compare(a.archived(), b.archived()) != 0 ? Boolean.compare(a.archived(), b.archived())
                        : a.name().compareToIgnoreCase(b.name()))
                .limit(LIMIT)
                .map(p -> new ProjectHit(p.key(), p.name(), p.archived()))
                .toList();
        String like = "%" + needle.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        List<PersonHit> people = jdbc.sql("""
                SELECT u.id, u.display_name, u.handle
                  FROM membership m JOIN user_account u ON u.id = m.user_id
                 WHERE m.organization_id = :org AND u.status = 'ACTIVE'
                   AND (lower(u.display_name) LIKE :like OR u.handle LIKE :like)
                 ORDER BY lower(u.display_name), u.id
                 LIMIT :limit
                """)
                .param("org", ctx.organizationId()).param("like", like).param("limit", LIMIT)
                .query((rs, n) -> new PersonHit(rs.getObject("id", UUID.class), rs.getString("display_name"),
                        rs.getString("handle")))
                .list();
        return new Results(text, issueHits, projectHits, people);
    }

    private static IssueHit issueHit(IssueSummary s) {
        return new IssueHit(s.key(), s.title(), s.status() == null ? null : s.status().name(),
                s.status() == null ? null : s.status().category(), s.projectKey());
    }
}
