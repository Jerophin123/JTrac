# Universal Tool Calling Protocol (UTCP) Integration (Experimental)

JTRAC natively implements the **Universal Tool Calling Protocol (UTCP)** as a standardized AI agent integration alongside the Model Context Protocol (MCP).

> **Status: Experimental**  
> UTCP is supported as an experimental protocol adapter in JTRAC. The feature is displayed as **UTCP** `Experimental` in settings. It provides full production-quality security, tenancy isolation, and audit logging while maintaining read-only tool boundaries during the experimental phase.

---

## 1. Specification & Protocol Details

- **Supported UTCP Version:** `1.0.1` (Pinned)
- **Manual Version:** `1.0.0`
- **Official Specification Reference:** [https://utcp.io/](https://utcp.io/) and [https://github.com/universal-tool-calling-protocol/utcp-specification](https://github.com/universal-tool-calling-protocol/utcp-specification)
- **Communication Protocol:** HTTP REST with JSON Schemas (`application/json`)
- **Execution Semantics:** Synchronous HTTP POST with parameterized arguments and standardized error response payloads (`application/problem+json`).

---

## 2. Architecture: Native Protocol Adapter

JTRAC adopts a clean layered architecture ensuring protocol adapters never duplicate domain logic:

```
┌────────────────────────────────────────────────────────┐
│                      AI Clients                        │
│   (MCP Clients)                      (UTCP Clients)    │
└──────────┬─────────────────────────────────┬───────────┘
           │                                 │
           ▼                                 ▼
┌──────────────────────┐          ┌──────────────────────┐
│     MCP Adapter      │          │     UTCP Adapter     │
│   (com.jtac.mcp)     │          │    (com.jtac.utcp)   │
└──────────┬───────────┘          └──────────┬───────────┘
           │                                 │
           └────────────────┬────────────────┘
                            │
                            ▼
┌────────────────────────────────────────────────────────┐
│                 Shared Integration Core                │
│  - Authentication & Token Verification                 │
│  - Multi-Tenant Isolation & Organization Resolution    │
│  - Project-level & Issue-level Authorization           │
│  - Attachment Access Checks & Anti-Traversal           │
│  - Audit Logging & Rate Limiting                       │
└───────────────────────────┬────────────────────────────┘
                            │
                            ▼
┌────────────────────────────────────────────────────────┐
│                JTRAC Application Services              │
│  (IssueAccess, IssueSearch, ProjectAccess,             │
│   WorkflowAccess, OrganizationAccess, UserDirectory)   │
└───────────────────────────┬────────────────────────────┘
                            │
                            ▼
┌────────────────────────────────────────────────────────┐
│                   PostgreSQL Database                  │
└────────────────────────────────────────────────────────┘
```

---

## 3. Provider Discovery & Manual Endpoints

Compatible UTCP clients discover JTRAC capabilities via the discovery manual:

- **Primary Discovery Route:** `GET /api/v1/utcp`
- **Specification Alias:** `GET /api/v1/utcp/manual`
- **Tool Listing Route:** `GET /api/v1/utcp/tools`
- **Individual Tool Schema Route:** `GET /api/v1/utcp/tools/{name}`
- **Unified Tool Dispatcher:** `POST /api/v1/utcp/execute`
- **Direct Tool Invocation:** `POST /api/v1/utcp/tools/{name}`
- **Secure Attachment Download:** `GET /api/v1/utcp/attachments/{issueKey}/{attachmentId}/download`

### Discovery Manual Format

Discovery responses conform to UTCP v1.0.1:

```json
{
  "utcp_version": "1.0.1",
  "manual_version": "1.0.0",
  "provider": {
    "name": "JTRAC",
    "description": "JTRAC Issue Tracking and Project Management System",
    "url": "https://tracker.example.com",
    "status": "experimental"
  },
  "tools": [
    {
      "name": "search_issues",
      "description": "Search issues across accessible projects using full-text query and filters.",
      "inputs": {
        "type": "object",
        "properties": {
          "query": { "type": "string", "description": "Full-text query string" },
          "projectKeys": { "type": "array", "items": { "type": "string" } },
          "pageSize": { "type": "integer", "default": 20 }
        }
      },
      "outputs": {
        "type": "object",
        "properties": {
          "items": { "type": "array" },
          "totalCount": { "type": "integer" }
        }
      },
      "tool_call_template": {
        "call_template_type": "http",
        "url": "https://tracker.example.com/api/v1/utcp/tools/search_issues",
        "http_method": "POST",
        "headers": {
          "Content-Type": "application/json"
        },
        "auth": {
          "auth_type": "api_key",
          "api_key": "Bearer ${JTRAC_API_TOKEN}",
          "var_name": "Authorization",
          "location": "header"
        }
      }
    }
  ]
}
```

---

## 4. Initial Tool Catalogue (24 Read-Only Tools)

To ensure maximum security during the experimental release, the tool catalogue is strictly read-only. Write operations (issue creation, comment creation, status transitions) are deliberately excluded.

### A. Issues (6 Tools)
- `search_issues`: Full-text search and filtering across projects, assignees, statuses and dates.
- `get_issue`: Detailed issue overview including fields, milestone, estimates and participants.
- `get_issue_status`: Current status, status category, and project key.
- `get_issue_history`: Audit changelog of edits and updates.
- `get_issue_activity`: Detailed activity timeline including events and system updates.
- `get_issue_relationships`: Subtasks, parent relations, and bidirectional links.

### B. Projects (3 Tools)
- `list_projects`: Accessible projects with keys, names, and archived status.
- `get_project`: In-depth project overview, workflow type, and issue templates.
- `get_project_metadata`: Issue types, custom field definitions, and milestone summaries.

### C. Comments (2 Tools)
- `list_issue_comments`: Chronological comments on an issue with substring and pagination filters.
- `get_comment_details`: Full comment content, author, and timestamp.

### D. Attachments (2 Tools)
- `list_issue_attachments`: Attached files with verified MIME types, byte sizes, and SHA-256 checksums.
- `get_attachment_metadata`: Individual attachment metadata and authorized download link.

### E. Workflows (3 Tools)
- `get_workflow`: Project workflow structure, status definitions, and step sequences.
- `get_available_statuses`: Active statuses configured for a project.
- `get_permitted_transitions`: Currently allowed status transitions for a specific issue given caller permissions.

### F. Users and Teams (3 Tools)
- `get_user_info`: Authorized public summary of a user in the organization (handle, name, avatar).
- `list_accessible_teams`: Teams accessible within the active organization.
- `get_team_membership`: Members of an accessible team.

### G. Reports and Activity (4 Tools)
- `get_project_activity`: Project audit activity stream.
- `get_issue_statistics`: Aggregated issue counts grouped by status, priority, type, or assignee.
- `list_fix_reports`: Fix reports and verification summaries.
- `get_fix_report`: Fix report verification tests and execution results.

---

## 5. Security & Tenant Isolation

1. **Authentication:** All tool executions require a valid JTRAC API token supplied via `Authorization: Bearer <token>`.
2. **Tenant Isolation:** Multi-tenant requests are resolved using:
   - Explicit `X-JTRAC-Organization: <org-slug>` header.
   - Token-restricted organization binding (if the token was issued for a single organization).
   - The user's default organization membership.
3. **Tenant Enumeration Defense:** Attempting to query an organization the user does not belong to returns `404 Not Found` rather than `403 Forbidden`, preventing organization discovery.
4. **Attachment Security:** Attachments are streamed through an authorized handler that checks project and issue read access. File paths and storage credentials are never exposed, and path traversal (`..`) attempts are rejected.
5. **Independent Lifecycle:** Users can independently enable or disable UTCP via the UI or `/api/v1/me/utcp/disable`. When disabled:
   - Tool calls receive `403 Forbidden`.
   - Web application usage is unaffected.
   - Existing audit events and settings are preserved.

---

## 6. Real Audit Logging

Every UTCP tool execution is recorded in the PostgreSQL table `utcp_activity`:
- `id`: UUID of the activity event
- `organization_id`: Tenant context
- `user_id`: Authenticated user
- `tool_name`: Invoked tool
- `target`: Issue key, project key, or entity identifier
- `client`: User-Agent reported by the AI client
- `outcome`: `OK`, `ERROR`, `DENIED`, or `RATE_LIMITED`
- `error_message`: Sanitized error message (if execution failed)
- `duration_ms`: Execution time in milliseconds
- `occurred_at`: Timestamp

Users can review real activity via `GET /api/v1/me/utcp/activity` or in the settings UI.

---

## 7. Connecting an AI Agent

### Step 1: Create an API Token
In JTRAC, navigate to **Account › Personal Tokens** (`/account/tokens`) and generate a token with read scopes:
- `issues:read`
- `projects:read`
- `attachments:read`
- `reports:read`

### Step 2: Configure the UTCP Client
In your UTCP agent runtime, configure JTRAC as a manual provider:

```json
{
  "name": "jtrac",
  "manual_url": "https://tracker.example.com/api/v1/utcp",
  "auth": {
    "auth_type": "api_key",
    "var_name": "Authorization",
    "location": "header",
    "api_key": "Bearer ${JTRAC_API_TOKEN}"
  }
}
```

The client will automatically fetch `https://tracker.example.com/api/v1/utcp`, discover the 24 tools, and construct calls to `/api/v1/utcp/tools/{name}`.

