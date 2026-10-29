-- AI agent connections over the Model Context Protocol (ADR 0010).
--
-- A connection belongs to one user in one organization and is addressed by an opaque,
-- random public id (/mcp/c_<id>). The URL only locates the connection: agents
-- authenticate with OAuth 2.1 access tokens issued for that URL (RFC 8707 resource).
-- Only SHA-256 hashes of tokens, authorization codes and request handles are stored.

CREATE TABLE mcp_connection (
    id              uuid        PRIMARY KEY,
    public_id       text        NOT NULL,
    organization_id uuid        NOT NULL,
    user_id         uuid        NOT NULL,
    name            text        NOT NULL,
    scopes          text[]      NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    last_used_at    timestamptz,
    last_client     text,
    expires_at      timestamptz,
    rotated_at      timestamptz,
    revoked_at      timestamptz,
    version         bigint      NOT NULL DEFAULT 0,
    -- Leaving the organization (or deleting the account) removes the connection.
    FOREIGN KEY (organization_id, user_id) REFERENCES membership (organization_id, user_id) ON DELETE CASCADE,
    CONSTRAINT mcp_connection_public_id_uq UNIQUE (public_id),
    CONSTRAINT mcp_connection_public_id_ck CHECK (public_id ~ '^c_[A-Za-z0-9_-]{32}$'),
    CONSTRAINT mcp_connection_name_ck CHECK (char_length(name) BETWEEN 1 AND 80),
    CONSTRAINT mcp_connection_scopes_ck CHECK (scopes <@ ARRAY['issues:read', 'attachments:read', 'projects:read',
        'notifications:read', 'reports:read', 'issues:comment', 'issues:write', 'issues:transition', 'reports:write']),
    CONSTRAINT mcp_connection_last_client_ck CHECK (last_client IS NULL OR char_length(last_client) <= 100)
);
CREATE INDEX mcp_connection_user_idx ON mcp_connection (user_id, created_at DESC);

-- OAuth clients registered dynamically (RFC 7591). All are public clients (PKCE, no secret).
CREATE TABLE mcp_oauth_client (
    client_id     text        PRIMARY KEY,
    client_name   text        NOT NULL,
    redirect_uris text[]      NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    last_used_at  timestamptz,
    CONSTRAINT mcp_oauth_client_id_ck CHECK (client_id ~ '^mcpc_[A-Za-z0-9_-]{32}$'),
    CONSTRAINT mcp_oauth_client_name_ck CHECK (char_length(client_name) BETWEEN 1 AND 100),
    CONSTRAINT mcp_oauth_client_redirects_ck CHECK (cardinality(redirect_uris) BETWEEN 1 AND 5)
);
CREATE INDEX mcp_oauth_client_unused_idx ON mcp_oauth_client (created_at) WHERE last_used_at IS NULL;

-- A pending authorization request (the consent screen), then its one-time authorization code.
CREATE TABLE mcp_authorization (
    id                    uuid        PRIMARY KEY,
    handle_hash           text        NOT NULL UNIQUE,
    client_id             text        NOT NULL REFERENCES mcp_oauth_client (client_id) ON DELETE CASCADE,
    connection_id         uuid        NOT NULL REFERENCES mcp_connection (id) ON DELETE CASCADE,
    redirect_uri          text        NOT NULL,
    state                 text,
    code_challenge        text        NOT NULL,
    requested_scopes      text[]      NOT NULL,
    created_at            timestamptz NOT NULL DEFAULT now(),
    expires_at            timestamptz NOT NULL,
    decided_at            timestamptz,
    approved_by           uuid        REFERENCES user_account (id) ON DELETE CASCADE,
    code_hash             text        UNIQUE,
    code_expires_at       timestamptz,
    code_used_at          timestamptz,
    CONSTRAINT mcp_authorization_state_ck CHECK (state IS NULL OR char_length(state) <= 500),
    CONSTRAINT mcp_authorization_challenge_ck CHECK (code_challenge ~ '^[A-Za-z0-9_-]{43}$')
);
CREATE INDEX mcp_authorization_expiry_idx ON mcp_authorization (expires_at);

-- Access and refresh tokens. A refresh token rotates on use; all tokens descending from one
-- authorization share a family, which is revoked as a whole when a refresh token is replayed.
CREATE TABLE mcp_token (
    id            uuid        PRIMARY KEY,
    family_id     uuid        NOT NULL,
    connection_id uuid        NOT NULL REFERENCES mcp_connection (id) ON DELETE CASCADE,
    client_id     text        NOT NULL REFERENCES mcp_oauth_client (client_id) ON DELETE CASCADE,
    kind          text        NOT NULL CHECK (kind IN ('ACCESS', 'REFRESH')),
    token_hash    text        NOT NULL UNIQUE,
    scopes        text[]      NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    expires_at    timestamptz NOT NULL,
    used_at       timestamptz,
    revoked_at    timestamptz
);
CREATE INDEX mcp_token_connection_idx ON mcp_token (connection_id) WHERE revoked_at IS NULL;
CREATE INDEX mcp_token_family_idx ON mcp_token (family_id);
CREATE INDEX mcp_token_expiry_idx ON mcp_token (expires_at);

-- What agents did through a connection (shown to the owner; kept for 90 days). Holds tool
-- names, outcomes and issue keys only, never arguments, issue content or tokens.
CREATE TABLE mcp_activity (
    id              uuid        PRIMARY KEY,
    connection_id   uuid        NOT NULL REFERENCES mcp_connection (id) ON DELETE CASCADE,
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    kind            text        NOT NULL CHECK (kind IN ('TOOL', 'RESOURCE', 'PROMPT', 'AUTH')),
    name            text        NOT NULL CHECK (char_length(name) <= 100),
    outcome         text        NOT NULL CHECK (outcome IN ('OK', 'ERROR', 'DENIED', 'RATE_LIMITED')),
    target          text        CHECK (char_length(target) <= 100),
    client          text        CHECK (char_length(client) <= 100),
    duration_ms     integer
);
CREATE INDEX mcp_activity_connection_idx ON mcp_activity (connection_id, occurred_at DESC);
CREATE INDEX mcp_activity_time_idx ON mcp_activity (occurred_at);

-- Fix reports submitted by agents. Everything in them is what the agent reported; JTRAC
-- does not run code or tests and never marks a report as independently verified.
CREATE TABLE mcp_fix_report (
    id                uuid        PRIMARY KEY,
    organization_id   uuid        NOT NULL,
    issue_id          uuid        NOT NULL,
    connection_id     uuid        REFERENCES mcp_connection (id) ON DELETE SET NULL,
    author_id         uuid        REFERENCES user_account (id) ON DELETE SET NULL,
    summary           text        NOT NULL CHECK (char_length(summary) BETWEEN 1 AND 500),
    root_cause        text        CHECK (char_length(root_cause) <= 20000),
    details           jsonb       NOT NULL DEFAULT '{}'::jsonb,
    comment_id        uuid,
    created_at        timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (organization_id, issue_id) REFERENCES issue (organization_id, id) ON DELETE CASCADE
);
CREATE INDEX mcp_fix_report_issue_idx ON mcp_fix_report (issue_id, created_at DESC);

CREATE TABLE mcp_verification (
    id            uuid        PRIMARY KEY,
    report_id     uuid        NOT NULL REFERENCES mcp_fix_report (id) ON DELETE CASCADE,
    outcome       text        NOT NULL CHECK (outcome IN ('PASSED', 'FAILED', 'PARTIAL', 'NOT_RUN')),
    command       text        CHECK (char_length(command) <= 2000),
    details       text        CHECK (char_length(details) <= 20000),
    recorded_by   uuid        REFERENCES user_account (id) ON DELETE SET NULL,
    connection_id uuid        REFERENCES mcp_connection (id) ON DELETE SET NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX mcp_verification_report_idx ON mcp_verification (report_id, created_at);

-- Retry protection for agent writes: the same request id on the same connection returns the
-- first result instead of creating a duplicate comment or fix report.
CREATE TABLE mcp_idempotency (
    connection_id uuid        NOT NULL REFERENCES mcp_connection (id) ON DELETE CASCADE,
    request_id    text        NOT NULL CHECK (char_length(request_id) BETWEEN 1 AND 100),
    tool          text        NOT NULL,
    result_ref    text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (connection_id, request_id)
);

COMMENT ON TABLE mcp_connection IS 'AI agent (MCP) connections: one user, one organization, opaque public id.';
COMMENT ON COLUMN mcp_connection.public_id IS 'Random locator in the MCP URL; never an authentication secret.';
COMMENT ON TABLE mcp_token IS 'OAuth tokens for MCP connections (SHA-256 hashes only).';
COMMENT ON TABLE mcp_fix_report IS 'Agent-reported fix reports; contents are reported, not verified by JTRAC.';
