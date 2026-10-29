# AI agent connections (MCP)

JTRAC lets AI coding agents work with your issues through the
[Model Context Protocol](https://modelcontextprotocol.io). An agent can search and read
issues, comments, history and attachments, get a structured brief of an issue, and, only if
you allow it, comment, change fields, move issues through the workflow and record fix
reports. The agent does the code work in your repository; JTRAC provides the issue context.

Design decisions: [ADR 0010](adr/0010-ai-agent-connections-mcp.md).

## For users

### Create a connection

1. Open **Account › AI agents** (`/account/ai-agent-connections`) and choose **New connection**.
2. Pick the organization and the permissions. The default, **Coding assistant**, is
   read-only. Write permissions are off unless you turn them on.
3. Copy the connection URL (`https://<your JTRAC>/mcp/c_…`) and follow the steps for your
   agent under **Manage › Set up an agent**.
4. The agent opens a browser page asking you to approve it. Check the agent name, where the
   result is sent, and the permissions, then approve.

The URL is not a password: an agent that has only the URL can do nothing until you approve
it. Never give an agent your password or an API token.

### Agents

All agents below sign in with OAuth in the browser; the commands contain only the URL.
Replace `<URL>` with your connection URL.

| Agent | Add the server | Sign in |
|---|---|---|
| Claude Code | `claude mcp add --transport http jtrac <URL>` | `/mcp` → Authenticate, or `claude mcp login jtrac` |
| OpenAI Codex CLI | `codex mcp add jtrac --url <URL>` | `codex mcp login jtrac` |
| Gemini CLI | `gemini mcp add --transport http jtrac <URL>` | `/mcp auth jtrac` |
| Google Antigravity | `~/.gemini/config/mcp_config.json`: `{"mcpServers":{"jtrac":{"serverUrl":"<URL>"}}}` | Agent settings › Customizations › Authenticate, then paste the code back |
| OpenCode | `opencode.json`: `{"mcp":{"jtrac":{"type":"remote","url":"<URL>"}}}` | `opencode mcp auth jtrac` |
| Cursor | `~/.cursor/mcp.json`: `{"mcpServers":{"jtrac":{"url":"<URL>"}}}` | Cursor Settings › MCP |
| VS Code (Copilot) | `.vscode/mcp.json`: `{"servers":{"jtrac":{"type":"http","url":"<URL>"}}}` | when prompted |
| Manus | Settings › Integrations › Custom MCP Servers › Add Server | OAuth in the browser |
| Any MCP client | Remote server, Streamable HTTP, `<URL>` | OAuth with dynamic client registration |

Syntax is taken from each vendor's documentation (October 2026). JTRAC's OAuth and MCP
implementation was exercised end to end with the official MCP TypeScript SDK client
(registration, PKCE, resource binding, tool calls, revocation); the individual agents
above were **not** run against JTRAC in this environment. Notes:

- **Hosted agents** (Antigravity's callback page, Manus, Cursor cloud agents) send the
  sign-in result to an HTTPS page of their own. Antigravity's and Cursor's are recognised
  by default; others (Manus does not publish its address) appear on the approval screen as
  *unrecognised*: check the address belongs to the agent before approving.
- **Cloud agents** (Manus, Cursor cloud agents) call JTRAC from the internet: your server
  must be reachable from there over HTTPS. Local agents only need to reach it from your
  computer.
- **VS Code** documents a pre-registered `oauth.clientId` but not dynamic registration;
  if its sign-in fails, use another agent.

### Permissions

| Permission | Lets the agent | Profiles |
|---|---|---|
| `issues:read` | search and read issues, comments, history, workflow options, saved filters; prepare fix context | all |
| `attachments:read` | read attachments (text, logs, images) | all |
| `projects:read` | read project details, workflows, assignable people, statistics | analyst, coding assistant, contributor |
| `reports:read` | read fix reports | coding assistant, contributor |
| `notifications:read` | read your notifications and preferences | custom only |
| `issues:comment` | comment as you | contributor |
| `issues:write` | change title, description, priority, assignee, labels, dates, milestone, custom fields, links | contributor |
| `issues:transition` | move issues through workflow transitions | contributor |
| `reports:write` | record fix reports and verification results | contributor |

Permissions only narrow: the agent can never do more than you can in the web app, in that
organization, right now. Changing them applies at the agent's next request.

### Manage, rotate, revoke

**Manage** shows the URL, the agents signed in (sign out one of them), recent activity
(tool name, issue key, outcome; kept 90 days, never contents), permissions, and:
**Change URL** (old URL stops working, all agents signed out), **Revoke** (stops all access
immediately) and **Delete**. *Connected* is shown only while an agent holds a valid sign-in.

## Security model

- **Authentication:** OAuth 2.1 authorization code with PKCE S256; public clients only;
  dynamic client registration; RFC 8707 `resource` binds every token to one connection URL
  (audience); RFC 9207 `iss` in the response. Access tokens live 1 hour, refresh tokens 30
  days and rotate on every use; replaying a used refresh token or code revokes the whole
  token family. Only SHA-256 hashes of tokens, codes and request handles are stored. Tokens
  are never logged, never accepted on the REST API, and API tokens are never accepted here.
- **Consent:** in the signed-in web app, by the connection owner only (others get "not
  found"). The screen shows the agent's self-reported name, the callback host (with warnings
  for local callbacks and unrecognised websites), the permissions granted and those
  requested but not enabled.
- **Every MCP request** re-checks: Origin (DNS-rebinding protection), token validity and
  audience, connection active, account active, organization membership, per-connection
  rate limit (240/min), protocol version. **Every tool** re-checks scopes, then the domain
  modules enforce project permissions, workflow transitions per role, field rules (hidden
  fields never returned) and optimistic locking. Issue keys, ids and handles supplied by the
  agent are resolved within the connection's organization and the user's visibility only.
- **Untrusted content:** results containing user-written text carry a notice; server
  instructions and prompts tell agents to treat issue text and attachments as evidence,
  never instructions. Attachments are never executed or rendered; text extraction is
  bounded (2 MB read, line-capped patterns); images are returned only if their type was
  verified by magic bytes; URLs inside content are never fetched.
- **Writes** are audited (`mcp.issue.commented`, `mcp.issue.updated`,
  `mcp.issue.transitioned`, `mcp.issue.linked`, `mcp.issue.unlinked`, `mcp.fix_report.created`,
  `mcp.verification.recorded`) with the connection id, and also appear in the issue history
  as the user's changes. Comments and fix reports accept a `requestId` so retries do not
  duplicate them. No tool deletes issues, comments or attachments.
- **Fix reports** are stored as *reported*; JTRAC never runs tests or marks a report
  verified, and never fetches the pull-request link.

## Tools

30 tools; an agent sees only those its connection allows.

| Group | Tools |
|---|---|
| Discovery and detail (`issues:read`) | `get_current_user`, `search_issues`, `get_issue`, `get_issue_comments`, `get_issue_history`, `find_similar_issues`, `get_allowed_transitions`, `list_saved_filters`, `run_saved_filter` |
| Task preparation (`issues:read`) | `prepare_fix_context` |
| Attachments (`attachments:read`) | `list_issue_attachments`, `read_attachment`, `analyze_log_attachment`, `search_attachment_text` |
| Projects (`projects:read`) | `list_projects`, `get_project`, `get_project_workflow`, `get_project_issue_statistics`, `list_assignable_users` |
| Notifications (`notifications:read`) | `get_my_notifications`, `get_notification_preferences` |
| Fix reports | `list_fix_reports`, `get_fix_report` (`reports:read`); `create_fix_report`, `record_verification_result` (`reports:write`) |
| Writes | `add_issue_comment` (`issues:comment`); `update_issue`, `link_issues`, `unlink_issues` (`issues:write`); `transition_issue` (`issues:transition`) |

**Resources:** `jtrac://current-user`, `jtrac://issues/{issueKey}`, `…/comments`,
`…/history`, `…/attachments`, `…/fix-context`, `jtrac://projects/{projectKey}`.
**Prompts:** `investigate_issue`, `fix_issue`, `analyze_attachments`,
`write_regression_tests`, `review_fix_report`, `prepare_issue_update` (generic instructions
plus the issue key; no issue content).

### Capability matrix (requested inventory → implementation)

Related read operations were combined into fewer tools with filters, as recommended.

| Requested | Implemented as | Status |
|---|---|---|
| search/list/my/reported/assigned/recent/unresolved issues, by error, by label | `search_issues` (query, assignee `me`, reporter `me`, resolved, sort, labels, text) | done |
| get issue, summary, fields, links, relationships | `get_issue` | done |
| similar issues | `find_similar_issues` (keyword overlap, labelled heuristic; no semantic search) | done |
| acceptance criteria, reproduction steps, expected/actual, environment | `prepare_fix_context` sections by heading | done (heuristic; JTRAC has no dedicated fields) |
| comments, search comments | `get_issue_comments` (`contains`) | done |
| history, activity, changes, status history, assignment history | `get_issue_history` (`field`, `kind`) | done |
| issue audit events | — | deferred: issue-level history covers it; the audit log is organization-admin data |
| list attachments, metadata, evidence, reproduction artifacts | `list_issue_attachments` | done |
| content, download, extract text, image content | `read_attachment` (text paging, image content, base64 ≤ 1 MB) | done; PDF/Office extraction deferred (no approved parser) |
| analyze log, search attachment text | `analyze_log_attachment`, `search_attachment_text` | done |
| projects: list, get, summary, configuration, members, statistics, workflows, search, versions/milestones | `list_projects`, `get_project` (incl. milestones), `get_project_workflow`, `get_project_issue_statistics`, `list_assignable_users` | done |
| components, project activity | — | not modelled in JTRAC |
| current user, assignable users, assignment options, user issue summary | `get_current_user`, `list_assignable_users`, `search_issues` | done |
| user profile, team members, availability | — | deferred / not modelled (no availability data; profiles limited to name and handle) |
| statuses, transitions, workflow, priorities, types, required fields, validate transition | `get_project_workflow`, `get_allowed_transitions`, `get_project` | done |
| prepare fix context, technical/related/regression context, error evidence | `prepare_fix_context` | done |
| prepare agent task, test plan, checklist, update draft, PR summary | prompts `fix_issue`, `write_regression_tests`, `prepare_issue_update`, `review_fix_report` | done as prompts (the agent writes them; JTRAC does not pretend to) |
| add comment | `add_issue_comment` | done |
| update summary/description/priority/assignee/labels | `update_issue` | done |
| update status | `transition_issue` | done |
| add/remove relationship | `link_issues`, `unlink_issues` | done |
| submit issue update | `add_issue_comment` + `transition_issue` | composed by the agent |
| attach fix report, record test results | `create_fix_report` (`postComment`), `record_verification_result` | done; attaching as a file deferred |
| fix reports: create, get, list, verification history | `create_fix_report`, `get_fix_report`, `list_fix_reports` | done |
| compare fix reports | — | deferred (the agent can compare two `get_fix_report` results) |
| releases, release issues, release notes | — | not modelled in JTRAC (milestones are exposed through `get_project`) |
| notifications, preferences | `get_my_notifications`, `get_notification_preferences` | done (read-only) |
| saved filters | `list_saved_filters`, `run_saved_filter` | done |

## For operators

| Setting | Default | Meaning |
|---|---|---|
| `jtac.public-url` | — | must be `https://…` for agent connections (loopback `http` allowed for development) |
| `jtac.mcp.enabled` (`JTRAC_MCP_ENABLED`) | `true` | turns the feature off entirely |
| `jtac.mcp.https-redirects` (`JTRAC_MCP_HTTPS_REDIRECTS`) | `ANY` | `ANY`, `ALLOWLIST` (only trusted hosts) or `NONE` (local agents only) |
| `jtac.mcp.trusted-redirect-hosts` | `antigravity.google,www.cursor.com` | hosted-agent callback hosts shown as recognised |
| `jtac.mcp.requests-per-minute` | `240` | per connection |
| `jtac.mcp.registrations-per-hour` | `60` | anonymous client registrations per IP |
| `jtac.mcp.access-token-ttl` / `refresh-token-ttl` | `1h` / `30d` | token lifetimes |
| `jtac.mcp.max-connections-per-user` | `20` | active connections |

- Behind a reverse proxy, forward `/mcp/`, `/oauth/` and `/.well-known/oauth-*` to JTRAC
  and set `server.forward-headers-strategy` as for the rest of the app. Do not buffer
  responses beyond normal JSON sizes; MCP here is plain request/response (no SSE).
- Endpoints: MCP `POST /mcp/c_<id>`; `GET /.well-known/oauth-authorization-server`;
  `GET /.well-known/oauth-protected-resource/mcp/c_<id>`; `POST /oauth/register`;
  `GET /oauth/authorize`; `POST /oauth/token`; `POST /oauth/revoke`.
- Rate limits are per instance (in memory), like the rest of JTRAC.
- A daily job (with `jtac.jobs.enabled`) removes expired authorizations and tokens, activity
  older than 90 days and registered clients that never completed a sign-in.

## Known limitations

- Not run with the actual agent applications in this environment (none were installed);
  verified with the official MCP TypeScript SDK client 1.32.2 and integration tests.
- Client ID Metadata Documents (MCP 2025-11-25 SHOULD) are not supported; clients use
  dynamic registration.
- No server-initiated notifications (stateless transport); agents poll with tools.
- Browser-based MCP clients cannot connect: the endpoints send no CORS headers and reject
  foreign `Origin`s (DNS-rebinding protection). Native agents and hosted agents' servers
  send no `Origin` and are unaffected.
- PDF and Office attachments are not text-extracted.
- Release packages (jlink runtime, installer smoke tests) were not rebuilt with this change.
