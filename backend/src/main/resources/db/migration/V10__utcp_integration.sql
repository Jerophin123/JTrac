-- V10: Universal Tool Calling Protocol (UTCP) integration (Experimental).
--
-- UTCP is an open standard allowing AI agents to discover and invoke tools directly
-- using native communication protocols (HTTP) without intermediary proxy wrappers.
-- Connections and activity are tracked with full tenant isolation and audit logging.

CREATE TABLE utcp_setting (
    id              uuid        PRIMARY KEY,
    user_id         uuid        NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    organization_id uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    enabled         boolean     NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    last_used_at    timestamptz,
    last_client     text,
    CONSTRAINT utcp_setting_user_org_uq UNIQUE (user_id, organization_id),
    CONSTRAINT utcp_setting_last_client_ck CHECK (last_client IS NULL OR char_length(last_client) <= 100),
    FOREIGN KEY (organization_id, user_id) REFERENCES membership (organization_id, user_id) ON DELETE CASCADE
);
CREATE INDEX utcp_setting_user_idx ON utcp_setting (user_id);
CREATE INDEX utcp_setting_org_idx ON utcp_setting (organization_id);

-- UTCP activity logs: tracks tool discovery and execution with outcome and timing.
-- Holds tool names, outcomes, and issue keys only; never secrets, tokens, or personal passwords.
CREATE TABLE utcp_activity (
    id              uuid        PRIMARY KEY,
    user_id         uuid        NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    organization_id uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    tool_name       text        NOT NULL CHECK (char_length(tool_name) <= 100),
    outcome         text        NOT NULL CHECK (outcome IN ('OK', 'ERROR', 'DENIED', 'RATE_LIMITED')),
    target          text        CHECK (char_length(target) <= 100),
    client          text        CHECK (char_length(client) <= 100),
    duration_ms     integer
);
CREATE INDEX utcp_activity_user_time_idx ON utcp_activity (user_id, occurred_at DESC);
CREATE INDEX utcp_activity_org_time_idx ON utcp_activity (organization_id, occurred_at DESC);
CREATE INDEX utcp_activity_time_idx ON utcp_activity (occurred_at);

COMMENT ON TABLE utcp_setting IS 'Per-user per-organization settings for the Universal Tool Calling Protocol (UTCP) integration.';
COMMENT ON TABLE utcp_activity IS 'Audit logs of tool calls executed via the UTCP provider.';

