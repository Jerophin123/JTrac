package com.jtac.collaboration.internal;

import com.jtac.identity.UserDirectory;
import com.jtac.identity.UserSummary;
import com.jtac.issue.IssueAccess;
import com.jtac.issue.IssueChanged;
import com.jtac.issue.IssueHandle;
import com.jtac.project.ProjectPermission;
import com.jtac.shared.Ids;
import com.jtac.shared.web.ApiErrors;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CommentService {

    static final Pattern MENTION = Pattern.compile("(?<![\\w@/])@([a-z0-9][a-z0-9_.-]{1,31})", Pattern.CASE_INSENSITIVE);
    static final int MAX_LENGTH = 50_000;

    private final JdbcClient jdbc;
    private final IssueAccess issues;
    private final UserDirectory users;
    private final ApplicationEventPublisher events;

    CommentService(JdbcClient jdbc, IssueAccess issues, UserDirectory users, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.issues = issues;
        this.users = users;
        this.events = events;
    }

    record Person(UUID id, String displayName, String handle) {
    }

    record Comment(UUID id, String body, Person author, Instant createdAt, Instant editedAt, long version,
            boolean canEdit, boolean canDelete) {
    }

    @Transactional(readOnly = true)
    List<Comment> list(IssueHandle issue) {
        record Row(UUID id, String body, UUID author, Instant created, Instant edited, long version) {
        }
        List<Row> rows = jdbc.sql("""
                SELECT id, body, author_id, created_at, edited_at, version FROM comment
                 WHERE issue_id = :issue AND organization_id = :org AND deleted_at IS NULL ORDER BY created_at, id
                """)
                .param("issue", issue.issueId()).param("org", issue.organizationId())
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("body"), rs.getObject("author_id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(), instant(rs.getObject("edited_at", OffsetDateTime.class)),
                        rs.getLong("version")))
                .list();
        Set<UUID> authors = new HashSet<>();
        rows.forEach(r -> {
            if (r.author() != null) {
                authors.add(r.author());
            }
        });
        Map<UUID, UserSummary> people = users.findAll(authors);
        UUID me = issue.project().actorId();
        boolean comment = issue.project().has(ProjectPermission.COMMENT);
        boolean deleteAny = issue.project().has(ProjectPermission.DELETE_ANY_COMMENT);
        return rows.stream().map(r -> new Comment(r.id(), r.body(), person(people, r.author()), r.created(), r.edited(),
                r.version(), comment && me.equals(r.author()), deleteAny || (comment && me.equals(r.author())))).toList();
    }

    @Transactional
    Comment add(IssueHandle issue, String body) {
        issue.project().require(ProjectPermission.COMMENT);
        String text = clean(body);
        UUID id = Ids.newId();
        jdbc.sql("INSERT INTO comment (id, organization_id, issue_id, author_id, body) VALUES (:id, :org, :issue, :author, :body)")
                .param("id", id).param("org", issue.organizationId()).param("issue", issue.issueId())
                .param("author", issue.project().actorId()).param("body", text).update();
        Set<UUID> mentioned = mentions(issue, text);
        issues.recordActivity(issue.organizationId(), issue.issueId(), issue.project().actorId(), "COMMENTED",
                Map.of("commentId", id.toString()));
        issues.watch(issue.organizationId(), issue.issueId(), issue.project().actorId());
        mentioned.forEach(user -> issues.watch(issue.organizationId(), issue.issueId(), user));
        events.publishEvent(new IssueChanged(Ids.newId(), issue.organizationId(), issue.project().projectId(), issue.issueId(),
                issue.key(), issue.title(), issue.project().actorId(), "COMMENTED", List.of(),
                Map.of("commentId", id.toString(), "mentionedUserIds", mentioned.stream().map(UUID::toString).toList(),
                        "excerpt", excerpt(text)), Instant.now()));
        return list(issue).stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    Comment edit(IssueHandle issue, UUID commentId, String body, long expectedVersion) {
        issue.project().require(ProjectPermission.COMMENT);
        UUID author = authorOf(issue, commentId);
        if (!issue.project().actorId().equals(author)) {
            throw ApiErrors.forbidden("Only the author can edit a comment.");
        }
        String text = clean(body);
        int updated = jdbc.sql("""
                UPDATE comment SET body = :body, edited_at = now(), updated_at = now(), version = version + 1
                 WHERE id = :id AND issue_id = :issue AND version = :version AND deleted_at IS NULL
                """)
                .param("body", text).param("id", commentId).param("issue", issue.issueId()).param("version", expectedVersion).update();
        if (updated == 0) {
            throw new OptimisticLockingFailureException("comment changed");
        }
        return list(issue).stream().filter(c -> c.id().equals(commentId)).findFirst().orElseThrow();
    }

    @Transactional
    void delete(IssueHandle issue, UUID commentId) {
        UUID author = authorOf(issue, commentId);
        boolean own = issue.project().actorId().equals(author) && issue.project().has(ProjectPermission.COMMENT);
        if (!own && !issue.project().has(ProjectPermission.DELETE_ANY_COMMENT)) {
            throw ApiErrors.forbidden("You cannot delete this comment.");
        }
        jdbc.sql("DELETE FROM comment WHERE id = :id AND issue_id = :issue").param("id", commentId).param("issue", issue.issueId()).update();
        issues.recordActivity(issue.organizationId(), issue.issueId(), issue.project().actorId(), "COMMENT_DELETED",
                Map.of("commentId", commentId.toString()));
    }

    /** Mentioned users who exist and can browse the issue (others are ignored silently). */
    Set<UUID> mentions(IssueHandle issue, String text) {
        Set<String> handles = new LinkedHashSet<>();
        Matcher matcher = MENTION.matcher(text);
        while (matcher.find() && handles.size() < 50) {
            handles.add(matcher.group(1).toLowerCase(java.util.Locale.ROOT));
        }
        Set<UUID> result = new LinkedHashSet<>();
        for (UserSummary user : users.findByHandles(handles).values()) {
            if (!user.id().equals(issue.project().actorId())
                    && issues.visibleFieldsFor(issue.organizationId(), issue.issueId(), user.id()).isPresent()) {
                result.add(user.id());
            }
        }
        return result;
    }

    /** The comment's author; {@code null} if the author's account was deleted. */
    private UUID authorOf(IssueHandle issue, UUID commentId) {
        record Author(UUID id) {
        }
        return jdbc.sql("SELECT author_id FROM comment WHERE id = :id AND issue_id = :issue AND deleted_at IS NULL")
                .param("id", commentId).param("issue", issue.issueId())
                .query((rs, n) -> new Author(rs.getObject("author_id", UUID.class))).optional()
                .orElseThrow(() -> ApiErrors.notFound("Comment")).id();
    }

    private static String clean(String body) {
        String text = body == null ? "" : body.strip();
        if (text.isEmpty() || text.length() > MAX_LENGTH) {
            throw ApiErrors.invalid("body", "Write 1 to " + MAX_LENGTH + " characters.");
        }
        return text;
    }

    static String excerpt(String text) {
        String flat = text.replaceAll("\\s+", " ");
        return flat.length() <= 200 ? flat : flat.substring(0, 199) + "…";
    }

    private static Person person(Map<UUID, UserSummary> people, UUID id) {
        if (id == null) {
            return new Person(null, "Deleted user", null);
        }
        UserSummary user = people.get(id);
        return user == null ? new Person(id, "Unknown user", null) : new Person(id, user.displayName(), user.handle());
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
