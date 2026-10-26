package com.jtac.collaboration.internal;

import com.jtac.identity.CurrentUser;
import com.jtac.issue.IssueAccess;
import com.jtac.issue.IssueHandle;
import com.jtac.organization.OrganizationAccess;
import com.jtac.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CommentController {

    private static final String COMMENTS = ApiPaths.V1 + "/orgs/{org}/issues/{issueKey}/comments";

    private final OrganizationAccess organizations;
    private final IssueAccess issues;
    private final CommentService comments;

    CommentController(OrganizationAccess organizations, IssueAccess issues, CommentService comments) {
        this.organizations = organizations;
        this.issues = issues;
        this.comments = comments;
    }

    private IssueHandle issue(CurrentUser user, String org, String key) {
        return issues.require(organizations.require(user, org), key);
    }

    @GetMapping(COMMENTS)
    List<CommentService.Comment> list(CurrentUser user, @PathVariable String org, @PathVariable String issueKey) {
        return comments.list(issue(user, org, issueKey));
    }

    @PostMapping(COMMENTS)
    ResponseEntity<CommentService.Comment> add(CurrentUser user, @PathVariable String org, @PathVariable String issueKey,
            @Valid @RequestBody Body body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(comments.add(issue(user, org, issueKey), body.body()));
    }

    @PatchMapping(COMMENTS + "/{commentId}")
    CommentService.Comment edit(CurrentUser user, @PathVariable String org, @PathVariable String issueKey,
            @PathVariable UUID commentId, @Valid @RequestBody Edit body) {
        return comments.edit(issue(user, org, issueKey), commentId, body.body(), body.version());
    }

    @DeleteMapping(COMMENTS + "/{commentId}")
    ResponseEntity<Void> delete(CurrentUser user, @PathVariable String org, @PathVariable String issueKey,
            @PathVariable UUID commentId) {
        comments.delete(issue(user, org, issueKey), commentId);
        return ResponseEntity.noContent().build();
    }

    record Body(@NotBlank @Size(max = 50_000) String body) {
    }

    record Edit(@NotBlank @Size(max = 50_000) String body, long version) {
    }
}
