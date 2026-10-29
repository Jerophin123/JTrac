package com.jtac.collaboration;

import com.jtac.issue.IssueHandle;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Comments for other modules, with the checks of the REST API. The {@link IssueHandle}
 * proves the caller can browse the issue; adding requires the COMMENT permission.
 */
public interface IssueComments {

    /** Comments in posting order. */
    List<Comment> list(IssueHandle issue);

    Comment add(IssueHandle issue, String body);

    /** @param authorId {@code null} when the author's account was deleted */
    record Comment(UUID id, String body, UUID authorId, String authorName, String authorHandle, Instant createdAt,
            Instant editedAt) {
    }
}
