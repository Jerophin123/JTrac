package com.jtac.attachment.internal;

import com.jtac.identity.CurrentUser;
import com.jtac.issue.IssueAccess;
import com.jtac.issue.IssueHandle;
import com.jtac.organization.OrganizationAccess;
import com.jtac.shared.web.ApiPaths;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
class AttachmentController {

    private static final String ATTACHMENTS = ApiPaths.V1 + "/orgs/{org}/issues/{issueKey}/attachments";

    private final OrganizationAccess organizations;
    private final IssueAccess issues;
    private final AttachmentService attachments;

    AttachmentController(OrganizationAccess organizations, IssueAccess issues, AttachmentService attachments) {
        this.organizations = organizations;
        this.issues = issues;
        this.attachments = attachments;
    }

    private IssueHandle issue(CurrentUser user, String org, String key) {
        return issues.require(organizations.require(user, org), key);
    }

    @GetMapping(ATTACHMENTS)
    List<AttachmentService.Attachment> list(CurrentUser user, @PathVariable String org, @PathVariable String issueKey) {
        return attachments.list(issue(user, org, issueKey));
    }

    @PostMapping(path = ATTACHMENTS, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<AttachmentService.Attachment> upload(CurrentUser user, @PathVariable String org, @PathVariable String issueKey,
            @RequestPart("file") MultipartFile file) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED).body(attachments.upload(issue(user, org, issueKey), file));
    }

    /** Authorized download. Never rendered as HTML: forced download unless a verified image is requested inline. */
    @GetMapping(ATTACHMENTS + "/{attachmentId}/content")
    ResponseEntity<Resource> download(CurrentUser user, @PathVariable String org, @PathVariable String issueKey,
            @PathVariable UUID attachmentId, @RequestParam(defaultValue = "false") boolean inline) {
        AttachmentService.StoredFile file = attachments.open(issue(user, org, issueKey), attachmentId);
        boolean showInline = inline && file.inline();
        ContentDisposition disposition = (showInline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(file.fileName(), java.nio.charset.StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; img-src 'self'; sandbox")
                .cacheControl(CacheControl.noCache().cachePrivate())
                .contentType(showInline ? MediaType.parseMediaType(file.contentType()) : MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.sizeBytes())
                .body(new FileSystemResource(file.path()));
    }

    @DeleteMapping(ATTACHMENTS + "/{attachmentId}")
    ResponseEntity<Void> delete(CurrentUser user, @PathVariable String org, @PathVariable String issueKey,
            @PathVariable UUID attachmentId) {
        attachments.delete(issue(user, org, issueKey), attachmentId);
        return ResponseEntity.noContent().build();
    }
}
