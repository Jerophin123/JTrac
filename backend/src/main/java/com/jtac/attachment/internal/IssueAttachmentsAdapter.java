package com.jtac.attachment.internal;

import com.jtac.attachment.IssueAttachments;
import com.jtac.issue.IssueHandle;
import com.jtac.shared.web.ApiErrors;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** {@link IssueAttachments} on top of {@link AttachmentService}. */
@Component
class IssueAttachmentsAdapter implements IssueAttachments {

    private final AttachmentService attachments;

    IssueAttachmentsAdapter(AttachmentService attachments) {
        this.attachments = attachments;
    }

    @Override
    public List<Attachment> list(IssueHandle issue) {
        return attachments.list(issue).stream().map(IssueAttachmentsAdapter::toPublic).toList();
    }

    @Override
    public Content read(IssueHandle issue, UUID attachmentId, int maxBytes) {
        Attachment attachment = list(issue).stream().filter(a -> a.id().equals(attachmentId)).findFirst()
                .orElseThrow(() -> ApiErrors.notFound("Attachment"));
        AttachmentService.StoredFile file = attachments.open(issue, attachmentId);
        try (InputStream in = Files.newInputStream(file.path())) {
            byte[] bytes = in.readNBytes(Math.max(0, maxBytes));
            return new Content(attachment, bytes, file.sizeBytes() > bytes.length);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read attachment", e);
        }
    }

    private static Attachment toPublic(AttachmentService.Attachment a) {
        return new Attachment(a.id(), a.fileName(), a.contentType(), a.sizeBytes(), a.sha256(), a.uploadedBy(), a.createdAt());
    }
}
