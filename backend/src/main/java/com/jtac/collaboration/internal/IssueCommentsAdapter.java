package com.jtac.collaboration.internal;

import com.jtac.collaboration.IssueComments;
import com.jtac.issue.IssueHandle;
import java.util.List;
import org.springframework.stereotype.Component;

/** {@link IssueComments} on top of {@link CommentService}. */
@Component
class IssueCommentsAdapter implements IssueComments {

    private final CommentService comments;

    IssueCommentsAdapter(CommentService comments) {
        this.comments = comments;
    }

    @Override
    public List<Comment> list(IssueHandle issue) {
        return comments.list(issue).stream().map(IssueCommentsAdapter::toPublic).toList();
    }

    @Override
    public Comment add(IssueHandle issue, String body) {
        return toPublic(comments.add(issue, body));
    }

    private static Comment toPublic(CommentService.Comment c) {
        return new Comment(c.id(), c.body(), c.author().id(), c.author().displayName(), c.author().handle(), c.createdAt(),
                c.editedAt());
    }
}
