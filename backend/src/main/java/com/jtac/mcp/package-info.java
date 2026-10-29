/**
 * AI agent connections over the Model Context Protocol (ADR 0010).
 *
 * <p>A user creates a <em>connection</em> for one organization and gives its URL
 * ({@code /mcp/c_<random>}) to an agent such as Claude Code, Codex, Gemini CLI or OpenCode.
 * The agent signs in with OAuth 2.1 (dynamic client registration, PKCE, resource-bound
 * tokens issued by this module after the user's explicit consent) and then calls tools
 * that read issues, comments, history, attachments and project context, and, when the user
 * enabled them, write comments, field changes, transitions and fix reports.
 *
 * <p>Every tool call re-checks the account, the organization membership, the connection's
 * permissions and the user's current project permissions and field rules, through the
 * public APIs of the issue, collaboration, attachment, project, workflow, search and
 * notification modules. The connection URL is a locator, never a credential.
 *
 * <p>This module has no public API; everything is under {@code internal}.
 */
@ApplicationModule(displayName = "AI agent connections (MCP)")
package com.jtac.mcp;

import org.springframework.modulith.ApplicationModule;
