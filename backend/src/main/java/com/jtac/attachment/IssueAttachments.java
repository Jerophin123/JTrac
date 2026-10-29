package com.jtac.attachment;

import com.jtac.issue.IssueHandle;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read access to issue attachments for other modules. The {@link IssueHandle} proves the
 * caller can browse the issue; attachments of other issues are reported as not found.
 * Storage locations are never exposed.
 */
public interface IssueAttachments {

    List<Attachment> list(IssueHandle issue);

    /**
     * Reads at most {@code maxBytes} from the start of the file.
     *
     * @throws com.jtac.shared.web.ApiErrors.NotFound if it does not belong to the issue
     */
    Content read(IssueHandle issue, UUID attachmentId, int maxBytes);

    /**
     * @param contentType a verified image type (by magic bytes) or {@code application/octet-stream}
     */
    record Attachment(UUID id, String fileName, String contentType, long sizeBytes, String sha256, String uploadedBy,
            Instant createdAt) {
    }

    /** The first bytes of a file; {@code truncated} when the file is longer than what was read. */
    record Content(Attachment attachment, byte[] bytes, boolean truncated) {
    }
}
