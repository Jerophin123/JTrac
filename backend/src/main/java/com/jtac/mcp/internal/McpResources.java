package com.jtac.mcp.internal;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * MCP resources: read-only JSON views of the same data as the tools, addressed by
 * {@code jtrac://} URIs, resolved per request with the same scope and permission checks.
 */
@Component
class McpResources {

    private static final Pattern ISSUE = Pattern.compile("jtrac://issues/([A-Za-z][A-Za-z0-9_]{0,19}-[0-9]{1,10})(/[a-z-]+)?");
    private static final Pattern PROJECT = Pattern.compile("jtrac://projects/([A-Za-z][A-Za-z0-9_]{0,19})");

    private final IssueTools issueTools;
    private final AttachmentTools attachmentTools;
    private final ProjectTools projectTools;
    private final PrepTools prepTools;
    private final ActivityLog activity;
    private final JsonMapper json;

    McpResources(IssueTools issueTools, AttachmentTools attachmentTools, ProjectTools projectTools, PrepTools prepTools,
            ActivityLog activity, JsonMapper json) {
        this.issueTools = issueTools;
        this.attachmentTools = attachmentTools;
        this.projectTools = projectTools;
        this.prepTools = prepTools;
        this.activity = activity;
        this.json = json;
    }

    List<McpStatelessServerFeatures.SyncResourceSpecification> resources() {
        McpSchema.Resource me = McpSchema.Resource.builder("jtrac://current-user", "current-user")
                .title("Current user and connection").mimeType("application/json")
                .description("The JTRAC user, organization and permissions of this connection").build();
        return List.of(new McpStatelessServerFeatures.SyncResourceSpecification(me,
                (context, request) -> read(context, request.uri(), null, (agent, args) -> call(issueTools, "get_current_user", agent, args),
                        Map.of())));
    }

    List<McpStatelessServerFeatures.SyncResourceTemplateSpecification> templates() {
        return List.of(
                template("jtrac://issues/{issueKey}", "issue", "Issue", "An issue with every field you may see"),
                template("jtrac://issues/{issueKey}/comments", "issue-comments", "Issue comments", "Comments on an issue"),
                template("jtrac://issues/{issueKey}/history", "issue-history", "Issue history", "The change history of an issue"),
                template("jtrac://issues/{issueKey}/attachments", "issue-attachments", "Issue attachments",
                        "Attachment metadata of an issue (needs attachments:read)"),
                template("jtrac://issues/{issueKey}/fix-context", "issue-fix-context", "Issue fix context",
                        "The prepare_fix_context brief of an issue"),
                template("jtrac://projects/{projectKey}", "project", "Project", "Project details (needs projects:read)"));
    }

    private McpStatelessServerFeatures.SyncResourceTemplateSpecification template(String uri, String name, String title,
            String description) {
        McpSchema.ResourceTemplate template = new McpSchema.ResourceTemplate(uri, name, title, description, "application/json",
                null, null, null);
        return new McpStatelessServerFeatures.SyncResourceTemplateSpecification(template, (context, request) -> resolve(context, request));
    }

    private McpSchema.ReadResourceResult resolve(McpTransportContext context, McpSchema.ReadResourceRequest request) {
        String uri = request.uri();
        Matcher issue = ISSUE.matcher(uri);
        if (issue.matches()) {
            Map<String, Object> args = Map.of("issueKey", issue.group(1));
            String part = issue.group(2) == null ? "" : issue.group(2);
            return switch (part) {
                case "" -> read(context, uri, Scope.ISSUES_READ, (a, x) -> call(issueTools, "get_issue", a, x), args);
                case "/comments" -> read(context, uri, Scope.ISSUES_READ, (a, x) -> call(issueTools, "get_issue_comments", a, x), args);
                case "/history" -> read(context, uri, Scope.ISSUES_READ, (a, x) -> call(issueTools, "get_issue_history", a, x), args);
                case "/attachments" -> read(context, uri, Scope.ATTACHMENTS_READ,
                        (a, x) -> call(attachmentTools, "list_issue_attachments", a, x), args);
                case "/fix-context" -> read(context, uri, Scope.ISSUES_READ, (a, x) -> prepTools.prepare(a, x), args);
                default -> throw new IllegalArgumentException("Unknown resource: " + uri);
            };
        }
        Matcher project = PROJECT.matcher(uri);
        if (project.matches()) {
            return read(context, uri, Scope.PROJECTS_READ, (a, x) -> call(projectTools, "get_project", a, x),
                    Map.of("projectKey", project.group(1)));
        }
        throw new IllegalArgumentException("Unknown resource: " + uri);
    }

    private McpSchema.ReadResourceResult read(McpTransportContext context, String uri, Scope scope,
            BiFunction<AgentRequest, Args, Object> reader, Map<String, Object> args) {
        AgentRequest agent = AgentRequest.from(context);
        ActivityLog.Outcome outcome = ActivityLog.Outcome.OK;
        try {
            if (scope != null && !agent.has(scope)) {
                outcome = ActivityLog.Outcome.DENIED;
                throw new IllegalArgumentException("This connection does not have the '" + scope.wire() + "' permission.");
            }
            Object value = McpServerConfiguration.unchecked("resource " + uri, () -> reader.apply(agent, new Args(args)));
            Object summary = value instanceof ToolDef.Output output ? output.summary() : value;
            return new McpSchema.ReadResourceResult(List.of(new McpSchema.TextResourceContents(uri, "application/json",
                    json.writeValueAsString(summary), null)), null);
        } catch (RuntimeException e) {
            if (outcome == ActivityLog.Outcome.OK) {
                outcome = ActivityLog.Outcome.ERROR;
            }
            throw e;
        } finally {
            activity.record(agent.connectionId(), ActivityLog.Kind.RESOURCE, uri.replaceAll("/[A-Za-z0-9_-]+-[0-9]+", "/{key}"),
                    outcome, new Args(args).target(), agent.clientName(), null);
        }
    }

    private static Object call(ToolDef.Provider provider, String tool, AgentRequest agent, Args args) {
        return provider.tools().stream().filter(t -> t.name().equals(tool)).findFirst().orElseThrow()
                .handler().handle(agent, args);
    }
}
