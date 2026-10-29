package com.jtac.mcp.internal;

import static com.jtac.mcp.internal.ToolDef.Kind.READ;

import com.jtac.attachment.IssueAttachments;
import com.jtac.issue.IssueHandle;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Attachment tools (scope {@code attachments:read}). Files are never executed, rendered or
 * followed: text is decoded as UTF-8 and pattern-matched within fixed size limits; images
 * whose type was verified by magic bytes at upload are returned as image content; other
 * formats (PDF, Office, archives) are not extracted.
 */
@Component
class AttachmentTools implements ToolDef.Provider {

    static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/gif", "image/webp");
    private static final int MAX_FILES_SEARCHED = 20;

    private final ToolSupport support;
    private final IssueAttachments attachments;
    private final McpProperties properties;

    AttachmentTools(ToolSupport support, IssueAttachments attachments, McpProperties properties) {
        this.support = support;
        this.attachments = attachments;
        this.properties = properties;
    }

    @Override
    public List<ToolDef> tools() {
        return List.of(
                new ToolDef("list_issue_attachments", "List issue attachments",
                        "Files attached to an issue: id, name, size, verified type, SHA-256, uploader and time. Use the id"
                                + " with read_attachment or analyze_log_attachment.",
                        Scope.ATTACHMENTS_READ, READ, ToolDef.Schema.object()
                                .str("issueKey", "Issue key", 32, true).build(), this::list),
                new ToolDef("read_attachment", "Read attachment",
                        "Reads an attachment. Text files (UTF-8: logs, JSON, CSV, source, Markdown) are returned as text,"
                                + " paged by characters; PNG/JPEG/GIF/WebP images as image content. Other formats are not"
                                + " extracted; set includeBase64 to receive small files base64-encoded instead.",
                        Scope.ATTACHMENTS_READ, READ, ToolDef.Schema.object()
                                .str("issueKey", "Issue key", 32, true)
                                .str("attachmentId", "Id from list_issue_attachments", 40, true)
                                .integer("offset", "First character to return (text files)", 0, 10_000_000)
                                .integer("maxChars", "Characters to return (default 20000)", 100, 60_000)
                                .bool("includeBase64", "For non-text, non-image files up to the size limit: return the bytes")
                                .build(), this::read),
                new ToolDef("analyze_log_attachment", "Analyze log attachment",
                        "Summarizes a text/log attachment without an AI model: line count, log level counts, exception"
                                + " types, first and last timestamps, the first error lines and up to five stack traces,"
                                + " with line numbers.",
                        Scope.ATTACHMENTS_READ, READ, ToolDef.Schema.object()
                                .str("issueKey", "Issue key", 32, true)
                                .str("attachmentId", "Id from list_issue_attachments", 40, true).build(), this::analyze),
                new ToolDef("search_attachment_text", "Search attachment text",
                        "Finds lines containing a text in an issue's text attachments (up to 20 files), with file and"
                                + " line number.",
                        Scope.ATTACHMENTS_READ, READ, ToolDef.Schema.object()
                                .str("issueKey", "Issue key", 32, true)
                                .str("query", "Text to find (literal, not a regular expression)", 200, true)
                                .bool("caseSensitive", "Match case (default false)")
                                .integer("maxMatches", "At most this many lines (default 50)", 1, 200).build(), this::searchText));
    }

    private Object list(AgentRequest request, Args args) {
        IssueHandle issue = support.issue(request, args.issueKey("issueKey"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("issueKey", issue.key());
        result.put("attachments", attachments.list(issue).stream().map(AttachmentTools::metadata).toList());
        result.put("contentNotice", ToolSupport.UNTRUSTED);
        return result;
    }

    static Map<String, Object> metadata(IssueAttachments.Attachment a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id());
        m.put("fileName", a.fileName());
        m.put("sizeBytes", a.sizeBytes());
        m.put("verifiedType", IMAGE_TYPES.contains(a.contentType()) ? a.contentType() : "unverified (treated as binary or text)");
        m.put("sha256", a.sha256());
        m.put("uploadedBy", a.uploadedBy());
        m.put("uploadedAt", a.createdAt());
        return m;
    }

    private Object read(AgentRequest request, Args args) {
        IssueHandle issue = support.issue(request, args.issueKey("issueKey"));
        UUID id = args.uuid("attachmentId");
        IssueAttachments.Attachment meta = attachments.list(issue).stream().filter(a -> a.id().equals(id)).findFirst()
                .orElseThrow(() -> new Args.Invalid("No attachment with that id on " + issue.key() + "."));
        Map<String, Object> summary = new LinkedHashMap<>(metadata(meta));
        if (IMAGE_TYPES.contains(meta.contentType())) {
            if (meta.sizeBytes() > properties.maxImageSize().toBytes()) {
                summary.put("note", "Image larger than " + properties.maxImageSize().toMegabytes() + " MB; not returned.");
                return summary;
            }
            IssueAttachments.Content content = attachments.read(issue, id, (int) properties.maxImageSize().toBytes());
            summary.put("note", "Image returned as image content.");
            return new ToolDef.Output(summary, List.of(new McpSchema.ImageContent(null,
                    Base64.getEncoder().encodeToString(content.bytes()), meta.contentType(), null)));
        }
        IssueAttachments.Content content = attachments.read(issue, id, (int) properties.maxAttachmentRead().toBytes());
        Optional<String> text = Evidence.decodeText(content.bytes(), content.truncated());
        if (text.isPresent()) {
            int offset = args.integer("offset", 0, 0, 10_000_000);
            int max = args.integer("maxChars", 20_000, 100, 60_000);
            String all = text.get();
            int from = Math.min(offset, all.length());
            int to = Math.min(all.length(), from + max);
            summary.put("format", "text");
            summary.put("charsAvailable", all.length());
            summary.put("fileTruncatedForReading", content.truncated());
            summary.put("offset", from);
            summary.put("nextOffset", to < all.length() ? to : null);
            summary.put("text", all.substring(from, to));
            summary.put("contentNotice", ToolSupport.UNTRUSTED);
            return summary;
        }
        summary.put("format", "binary");
        if (args.bool("includeBase64", false)) {
            if (meta.sizeBytes() > properties.maxBlobSize().toBytes()) {
                summary.put("note", "File larger than the " + properties.maxBlobSize().toKilobytes() + " KB limit; not returned.");
                return summary;
            }
            IssueAttachments.Content bytes = attachments.read(issue, id, (int) properties.maxBlobSize().toBytes());
            summary.put("note", "Returned as an embedded base64 resource. JTRAC did not inspect its contents.");
            return new ToolDef.Output(summary, List.of(new McpSchema.EmbeddedResource(null, new McpSchema.BlobResourceContents(
                    "jtrac://issues/" + issue.key() + "/attachments/" + id, "application/octet-stream",
                    Base64.getEncoder().encodeToString(bytes.bytes()), null), null)));
        }
        summary.put("note", "Not a UTF-8 text file. JTRAC does not extract text from PDF, Office or archive formats;"
                + " use includeBase64 for small files.");
        return summary;
    }

    private Object analyze(AgentRequest request, Args args) {
        IssueHandle issue = support.issue(request, args.issueKey("issueKey"));
        UUID id = args.uuid("attachmentId");
        IssueAttachments.Content content = attachments.read(issue, id, (int) properties.maxAttachmentRead().toBytes());
        Map<String, Object> result = new LinkedHashMap<>(metadata(content.attachment()));
        Optional<String> text = Evidence.decodeText(content.bytes(), content.truncated());
        if (text.isEmpty()) {
            result.put("note", "Not a UTF-8 text file; nothing to analyze.");
            return result;
        }
        result.put("analyzedBytes", content.bytes().length);
        result.put("fileTruncatedForAnalysis", content.truncated());
        result.put("analysis", Evidence.analyzeLog(text.get()));
        result.put("method", "Pattern matching on log levels, exception names and stack frames; no AI model.");
        result.put("contentNotice", ToolSupport.UNTRUSTED);
        return result;
    }

    private Object searchText(AgentRequest request, Args args) {
        IssueHandle issue = support.issue(request, args.issueKey("issueKey"));
        String query = args.required("query");
        boolean caseSensitive = args.bool("caseSensitive", false);
        int max = args.integer("maxMatches", 50, 1, 200);
        List<Map<String, Object>> matches = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int searched = 0;
        for (IssueAttachments.Attachment a : attachments.list(issue)) {
            if (IMAGE_TYPES.contains(a.contentType())) {
                continue;
            }
            if (searched >= MAX_FILES_SEARCHED || matches.size() >= max) {
                skipped.add(a.fileName());
                continue;
            }
            searched++;
            IssueAttachments.Content content = attachments.read(issue, a.id(), (int) properties.maxAttachmentRead().toBytes());
            Optional<String> text = Evidence.decodeText(content.bytes(), content.truncated());
            if (text.isEmpty()) {
                skipped.add(a.fileName() + " (binary)");
                continue;
            }
            for (Map<String, Object> hit : Evidence.grep(text.get(), query, caseSensitive, max - matches.size())) {
                Map<String, Object> m = new LinkedHashMap<>(hit);
                m.put("attachmentId", a.id());
                m.put("fileName", a.fileName());
                matches.add(m);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("issueKey", issue.key());
        result.put("matches", matches);
        result.put("filesSearched", searched);
        result.put("skipped", skipped);
        result.put("contentNotice", ToolSupport.UNTRUSTED);
        return result;
    }
}
