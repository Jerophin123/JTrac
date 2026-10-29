package com.jtac.mcp.internal;

import com.jtac.identity.CurrentUser;
import com.jtac.organization.OrgContext;
import io.modelcontextprotocol.common.McpTransportContext;
import java.util.Set;
import java.util.UUID;

/**
 * One authenticated MCP request: the connection, the user's freshly resolved organization
 * context (membership proven for this request) and the effective scopes.
 */
record AgentRequest(OAuthService.AgentPrincipal principal, OrgContext org) {

    static final String KEY = "jtrac.agentRequest";

    static AgentRequest from(McpTransportContext context) {
        if (context != null && context.get(KEY) instanceof AgentRequest request) {
            return request;
        }
        throw new IllegalStateException("MCP request without an authenticated agent");
    }

    CurrentUser user() {
        return org.actor();
    }

    Set<Scope> scopes() {
        return principal.scopes();
    }

    boolean has(Scope scope) {
        return principal.scopes().contains(scope);
    }

    UUID connectionId() {
        return principal.connection().id();
    }

    String clientName() {
        return principal.clientName();
    }
}
