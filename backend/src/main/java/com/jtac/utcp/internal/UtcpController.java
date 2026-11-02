package com.jtac.utcp.internal;

import com.jtac.attachment.IssueAttachments;
import com.jtac.identity.CurrentUser;
import com.jtac.issue.IssueAccess;
import com.jtac.issue.IssueHandle;
import com.jtac.organization.OrgContext;
import com.jtac.organization.OrganizationAccess;
import com.jtac.shared.web.ApiErrors;
import com.jtac.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Native UTCP provider and discovery controller conforming to the official UTCP 1.0.1 specification.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/utcp")
class UtcpController {

    private final UtcpService utcpService;
    private final UtcpUrls urls;
    private final IssueAccess issueAccess;
    private final IssueAttachments attachments;
    private final OrganizationAccess organizations;

    UtcpController(
            UtcpService utcpService,
            UtcpUrls urls,
            IssueAccess issueAccess,
            IssueAttachments attachments,
            OrganizationAccess organizations
    ) {
        this.utcpService = utcpService;
        this.urls = urls;
        this.issueAccess = issueAccess;
        this.attachments = attachments;
        this.organizations = organizations;
    }

    record ExecuteRequest(String tool, Map<String, Object> arguments) {}

    record ToolSummary(
            String name,
            String description,
            String category,
            String scope,
            Map<String, Object> inputs,
            Map<String, Object> outputs
    ) {}

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<UtcpManual.Manual> manual(HttpServletRequest request) {
        return ResponseEntity.ok()
                .header("X-Provider", "JTRAC")
                .body(utcpService.getManual(urls.baseUrl(request)));
    }

    @GetMapping(value = "/manual", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<UtcpManual.Manual> manualAlias(HttpServletRequest request) {
        return ResponseEntity.ok()
                .header("X-Provider", "JTRAC")
                .body(utcpService.getManual(urls.baseUrl(request)));
    }

    @GetMapping(value = "/tools", produces = MediaType.APPLICATION_JSON_VALUE)
    List<ToolSummary> listTools() {
        return utcpService.listTools().stream().map(t -> new ToolSummary(
                t.name(),
                t.description(),
                t.category(),
                t.scope(),
                t.inputSchema(),
                t.outputSchema()
        )).toList();
    }

    @GetMapping(value = "/tools/{name}", produces = MediaType.APPLICATION_JSON_VALUE)
    UtcpManual.Tool getTool(@PathVariable String name) {
        return utcpService.getManual().tools().stream()
                .filter(t -> t.name().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> ApiErrors.notFound("Tool not found: " + name));
    }

    @PostMapping(value = "/tools/{name}", produces = MediaType.APPLICATION_JSON_VALUE)
    Object callTool(
            CurrentUser user,
            @PathVariable String name,
            @RequestBody(required = false) Map<String, Object> bodyArguments,
            @RequestParam(required = false) Map<String, Object> queryArguments,
            @RequestHeader(value = "X-JTRAC-Organization", required = false) String orgHeader,
            @RequestHeader(value = "User-Agent", required = false) String clientAgent
    ) {
        if (user == null) {
            throw ApiErrors.forbidden("Authentication required to invoke UTCP tools.");
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (queryArguments != null) {
            arguments.putAll(queryArguments);
        }
        if (bodyArguments != null) {
            arguments.putAll(bodyArguments);
        }
        return utcpService.execute(user, orgHeader, name, arguments, clientAgent);
    }

    @PostMapping(value = "/execute", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    Object execute(
            CurrentUser user,
            @RequestBody ExecuteRequest body,
            @RequestHeader(value = "X-JTRAC-Organization", required = false) String orgHeader,
            @RequestHeader(value = "User-Agent", required = false) String clientAgent
    ) {
        if (user == null) {
            throw ApiErrors.forbidden("Authentication required to invoke UTCP tools.");
        }
        if (body == null || body.tool() == null || body.tool().isBlank()) {
            throw ApiErrors.invalid("tool", "Tool name is required.");
        }
        return utcpService.execute(user, orgHeader, body.tool(), body.arguments(), clientAgent);
    }

    @GetMapping("/attachments/{issueKey}/{attachmentId}/download")
    ResponseEntity<byte[]> downloadAttachment(
            CurrentUser user,
            @PathVariable String issueKey,
            @PathVariable UUID attachmentId,
            @RequestHeader(value = "X-JTRAC-Organization", required = false) String orgHeader
    ) {
        if (user == null) {
            throw ApiErrors.forbidden("Authentication required to download attachments.");
        }

        OrgContext org = utcpService.resolveOrg(user, orgHeader);
        IssueHandle handle = issueAccess.require(org, issueKey);
        IssueAttachments.Content content = attachments.read(handle, attachmentId, 25 * 1024 * 1024);

        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(content.attachment().contentType());
        } catch (Exception e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }

        String safeFilename = content.attachment().fileName().replaceAll("[\\r\\n\"]", "_");
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeFilename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-cache, no-store")
                .body(content.bytes());
    }
}
