package com.jtac.attachment.internal;

import com.jtac.issue.IssueAccess;
import com.jtac.issue.IssueChanged;
import com.jtac.issue.IssueHandle;
import com.jtac.project.ProjectPermission;
import com.jtac.shared.Ids;
import com.jtac.shared.web.ApiErrors;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
class AttachmentService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    /** Types that may be displayed inline, verified by magic bytes. Everything else downloads. */
    static final Set<String> INLINE_IMAGES = Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

    private final JdbcClient jdbc;
    private final AttachmentProperties properties;
    private final IssueAccess issues;
    private final ApplicationEventPublisher events;

    AttachmentService(JdbcClient jdbc, AttachmentProperties properties, IssueAccess issues, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.issues = issues;
        this.events = events;
    }

    record Attachment(UUID id, String fileName, String contentType, long sizeBytes, String sha256, String uploadedBy,
            Instant createdAt, boolean inline, boolean canDelete) {
    }

    record StoredFile(Path path, String fileName, String contentType, long sizeBytes, boolean inline) {
    }

    @Transactional(readOnly = true)
    List<Attachment> list(IssueHandle issue) {
        return jdbc.sql("""
                SELECT a.id, a.file_name, a.content_type, a.size_bytes, a.sha256, a.uploader_id, u.display_name, a.created_at
                  FROM attachment a LEFT JOIN user_account u ON u.id = a.uploader_id
                 WHERE a.issue_id = :issue AND a.organization_id = :org ORDER BY a.created_at, a.id
                """)
                .param("issue", issue.issueId()).param("org", issue.organizationId())
                .query((rs, n) -> {
                    UUID uploader = rs.getObject("uploader_id", UUID.class);
                    return new Attachment(rs.getObject("id", UUID.class), rs.getString("file_name"), rs.getString("content_type"),
                            rs.getLong("size_bytes"), rs.getString("sha256"), rs.getString("display_name"),
                            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                            INLINE_IMAGES.contains(rs.getString("content_type")), canDelete(issue, uploader));
                })
                .list();
    }

    @Transactional
    Attachment upload(IssueHandle issue, MultipartFile file) throws IOException {
        issue.project().require(ProjectPermission.MANAGE_ATTACHMENTS);
        if (file == null || file.isEmpty()) {
            throw ApiErrors.invalid("file", "Choose a non-empty file.");
        }
        if (file.getSize() > properties.maxFileSize().toBytes()) {
            throw ApiErrors.invalid("file", "Files can be at most " + properties.maxFileSize().toMegabytes() + " MB.");
        }
        long existing = jdbc.sql("SELECT count(*) FROM attachment WHERE issue_id = :issue").param("issue", issue.issueId())
                .query(Long.class).single();
        if (existing >= properties.maxPerIssue()) {
            throw ApiErrors.conflict("An issue can have at most " + properties.maxPerIssue() + " attachments.");
        }
        String fileName = sanitizeFileName(file.getOriginalFilename());
        UUID id = Ids.newId();
        String storageKey = issue.organizationId() + "/" + id;
        Path target = resolve(storageKey);
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), "upload-", ".part");
        String sha256;
        long size;
        byte[] head = new byte[16];
        int headLength;
        try (InputStream in = file.getInputStream();
                OutputStream out = new DigestOutputStream(Files.newOutputStream(temp), sha256Digest())) {
            headLength = in.readNBytes(head, 0, head.length);
            out.write(head, 0, headLength);
            size = headLength + in.transferTo(out);
            sha256 = HexFormat.of().formatHex(((DigestOutputStream) out).getMessageDigest().digest());
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        if (size > properties.maxFileSize().toBytes()) {
            Files.deleteIfExists(temp);
            throw ApiErrors.invalid("file", "Files can be at most " + properties.maxFileSize().toMegabytes() + " MB.");
        }
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        deleteFileOnRollback(target);
        String contentType = detectImage(head, headLength).orElse("application/octet-stream");
        jdbc.sql("""
                INSERT INTO attachment (id, organization_id, issue_id, uploader_id, file_name, content_type, size_bytes, sha256, storage_key)
                VALUES (:id, :org, :issue, :uploader, :name, :type, :size, :sha, :key)
                """)
                .param("id", id).param("org", issue.organizationId()).param("issue", issue.issueId())
                .param("uploader", issue.project().actorId()).param("name", fileName).param("type", contentType)
                .param("size", size).param("sha", sha256).param("key", storageKey).update();
        issues.recordActivity(issue.organizationId(), issue.issueId(), issue.project().actorId(), "ATTACHMENT_ADDED",
                Map.of("fileName", fileName, "attachmentId", id.toString()));
        events.publishEvent(new IssueChanged(Ids.newId(), issue.organizationId(), issue.project().projectId(), issue.issueId(),
                issue.key(), issue.title(), issue.project().actorId(), "ATTACHMENT_ADDED", List.of(),
                Map.of("fileName", fileName), Instant.now()));
        return list(issue).stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    StoredFile open(IssueHandle issue, UUID attachmentId) {
        record Row(String fileName, String type, long size, String key) {
        }
        Row row = jdbc.sql("SELECT file_name, content_type, size_bytes, storage_key FROM attachment WHERE id = :id AND issue_id = :issue")
                .param("id", attachmentId).param("issue", issue.issueId())
                .query((rs, n) -> new Row(rs.getString("file_name"), rs.getString("content_type"), rs.getLong("size_bytes"),
                        rs.getString("storage_key")))
                .optional().orElseThrow(() -> ApiErrors.notFound("Attachment"));
        Path path = resolve(row.key());
        if (!Files.isRegularFile(path)) {
            log.error("Attachment file missing for id={}", attachmentId);
            throw ApiErrors.notFound("Attachment file");
        }
        return new StoredFile(path, row.fileName(), row.type(), row.size(), INLINE_IMAGES.contains(row.type()));
    }

    @Transactional
    void delete(IssueHandle issue, UUID attachmentId) {
        record Row(UUID uploader, String key, String fileName) {
        }
        Row row = jdbc.sql("SELECT uploader_id, storage_key, file_name FROM attachment WHERE id = :id AND issue_id = :issue")
                .param("id", attachmentId).param("issue", issue.issueId())
                .query((rs, n) -> new Row(rs.getObject("uploader_id", UUID.class), rs.getString("storage_key"), rs.getString("file_name")))
                .optional().orElseThrow(() -> ApiErrors.notFound("Attachment"));
        if (!canDelete(issue, row.uploader())) {
            throw ApiErrors.forbidden("You cannot delete this attachment.");
        }
        jdbc.sql("DELETE FROM attachment WHERE id = :id").param("id", attachmentId).update();
        Path path = resolve(row.key());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("Could not delete attachment file (will be swept): {}", e.getClass().getSimpleName());
                }
            }
        });
        issues.recordActivity(issue.organizationId(), issue.issueId(), issue.project().actorId(), "ATTACHMENT_REMOVED",
                Map.of("fileName", row.fileName()));
    }

    private static boolean canDelete(IssueHandle issue, UUID uploader) {
        return issue.project().has(ProjectPermission.MANAGE_PROJECT)
                || (issue.project().has(ProjectPermission.MANAGE_ATTACHMENTS) && issue.project().actorId().equals(uploader));
    }

    Path resolve(String storageKey) {
        Path root = properties.directory().toAbsolutePath().normalize();
        Path path = root.resolve(storageKey).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalStateException("Invalid storage key");
        }
        return path;
    }

    private static void deleteFileOnRollback(Path path) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // swept later
                    }
                }
            }
        });
    }

    static String sanitizeFileName(String original) {
        String name = original == null ? "" : original;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("[\\p{Cntrl}\"<>|:*?]", "_").strip();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            name = "attachment";
        }
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }

    /** Magic-byte detection for the inline-safe image types. */
    static Optional<String> detectImage(byte[] head, int length) {
        if (length >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
            return Optional.of("image/png");
        }
        if (length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return Optional.of("image/jpeg");
        }
        if (length >= 6 && head[0] == 'G' && head[1] == 'I' && head[2] == 'F' && head[3] == '8') {
            return Optional.of("image/gif");
        }
        if (length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            return Optional.of("image/webp");
        }
        return Optional.empty();
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
