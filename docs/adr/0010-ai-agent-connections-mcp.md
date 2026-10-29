# ADR 0010: AI agent connections over MCP with a built-in OAuth 2.1 server

- Status: Accepted
- Date: 2026-10-28

## Context

Users want coding agents (Claude Code, OpenAI Codex, Gemini CLI, Google Antigravity,
OpenCode, Cursor, Manus and other Model Context Protocol clients) to read their JTRAC issues
and, when allowed, update them. The MCP authorization specification (2025-11-25) requires
an OAuth 2.1 flow with protected resource metadata (RFC 9728), authorization server
metadata (RFC 8414), PKCE, resource indicators (RFC 8707) and audience-checked tokens.
JTRAC is self-hosted, so there is no external identity provider to delegate to, and the
existing authentication is a browser session plus personal API tokens (ADR 0005).

## Decision

1. **A new module `mcp`** (internal only) serves MCP over Streamable HTTP in *stateless*
   mode using the official MCP Java SDK (`io.modelcontextprotocol.sdk:mcp-core` and
   `mcp-json-jackson3` 2.1.0, Jackson 3 as in Spring Boot 4). No server-initiated streams,
   no sessions: every request is authenticated and authorized from scratch.
2. **One connection = one OAuth protected resource.** A connection belongs to one user and
   one organization and is addressed by `/mcp/c_<192 random bits>`. Tokens are bound to the
   connection (audience); the URL is a locator, never a credential. A membership foreign key
   deletes connections when the user leaves the organization.
3. **A narrow built-in authorization server** instead of Spring Authorization Server: public
   clients only, dynamic client registration (RFC 7591), authorization code + PKCE S256,
   RFC 9207 `iss`, opaque tokens stored as SHA-256 hashes (1 h access, 30 d rotating
   refresh with replay detection), RFC 7009 revocation. Consent happens in the signed-in web
   app (session + CSRF, ADR 0005); only the connection's owner can approve. This keeps the
   whole flow inside existing patterns (hashed tokens, `JdbcClient`, audit log) and avoids
   a second login system, at the cost of owning ~600 lines of protocol code, covered by
   integration tests and an end-to-end run with the official MCP TypeScript SDK client.
4. **Scopes narrow, permissions decide.** Nine scopes (five read, four write; writes off by
   default) filter which tools an agent sees and may call. Every call additionally goes
   through the public APIs of the domain modules (`IssueDetails`, `IssueEditing`,
   `IssueComments`, `IssueAttachments`, `SavedViews`, `Inbox`, plus existing ones), so
   project permissions, workflow transitions per role, field rules and optimistic locking
   apply exactly as in the web app. No tool deletes anything.
5. **Redirect URIs:** loopback `http` callbacks (any port) always; `https` callbacks per
   `jtac.mcp.https-redirects` (`ANY` by default with a consent-screen warning for hosts not
   in `trusted-redirect-hosts`, or `ALLOWLIST`/`NONE`). Hosted agents (Antigravity, Manus,
   Cursor cloud agents) need HTTPS callbacks; their hosts are shown prominently on consent.
6. OAuth and MCP require HTTPS (`jtac.public-url` must be `https://`), except on loopback
   for development. The issuer and resource URLs derive from `jtac.public-url`, never from
   request headers.

## Consequences

- New tables (V9): `mcp_connection`, `mcp_oauth_client`, `mcp_authorization`, `mcp_token`,
  `mcp_activity`, `mcp_fix_report`, `mcp_verification`, `mcp_idempotency`.
- Rate limits (per connection, per IP for registration/authorize/token) are in memory, like
  the rest of JTRAC (ADR 0005): multiple instances would need a shared store.
- Client ID Metadata Documents (a SHOULD in MCP 2025-11-25) are not supported yet: the server
  would have to fetch client-supplied URLs (SSRF surface). Clients fall back to dynamic
  registration, which every target agent supports.
- Unused dynamically registered clients are deleted after a day by a maintenance job.

## Alternatives considered

- **Spring Authorization Server (Spring Security 7):** full-featured, but needs its own
  schema and login/consent integration, and open dynamic registration and RFC 8707 audience
  binding require customisation; more moving parts than the narrow profile MCP needs.
- **Personal API tokens as MCP credentials:** simplest, but MCP clients expect OAuth, users
  would paste long-lived secrets into config files, and there is no consent or audience.
- **A separate MCP server process:** duplicates authorization logic and deployment; rejected
  in favour of reusing the domain modules in-process.
