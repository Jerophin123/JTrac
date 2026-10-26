-- V3: tenancy — organizations, memberships, invitations, teams; personal API tokens.
-- Tenant isolation: every tenant-owned row carries organization_id and references its
-- parents through composite (organization_id, id) foreign keys, so a row can never point
-- at data of another organization.

CREATE TABLE organization (
    id         uuid        PRIMARY KEY,
    slug       text        NOT NULL,
    name       text        NOT NULL,
    settings   jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    version    bigint      NOT NULL DEFAULT 0,
    CONSTRAINT organization_slug_ck CHECK (slug ~ '^[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?$'),
    CONSTRAINT organization_name_ck CHECK (char_length(name) BETWEEN 1 AND 80)
);
CREATE UNIQUE INDEX organization_slug_uk ON organization (slug);

CREATE TABLE membership (
    organization_id uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    user_id         uuid        NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    role            text        NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER', 'GUEST')),
    joined_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, user_id)
);
CREATE INDEX membership_user_idx ON membership (user_id);

CREATE TABLE invitation (
    id              uuid        PRIMARY KEY,
    organization_id uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    email           text        NOT NULL CHECK (char_length(email) BETWEEN 3 AND 320),
    role            text        NOT NULL CHECK (role IN ('ADMIN', 'MEMBER', 'GUEST')),
    token_hash      text        NOT NULL UNIQUE,
    invited_by      uuid        REFERENCES user_account (id) ON DELETE SET NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    accepted_at     timestamptz,
    accepted_by     uuid        REFERENCES user_account (id) ON DELETE SET NULL,
    revoked_at      timestamptz
);
CREATE UNIQUE INDEX invitation_pending_uk ON invitation (organization_id, lower(email))
    WHERE accepted_at IS NULL AND revoked_at IS NULL;

CREATE TABLE team (
    id              uuid        PRIMARY KEY,
    organization_id uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    name            text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 60),
    description     text        CHECK (char_length(description) <= 500),
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, id)
);
CREATE UNIQUE INDEX team_name_uk ON team (organization_id, lower(name));

CREATE TABLE team_member (
    team_id         uuid NOT NULL,
    organization_id uuid NOT NULL,
    user_id         uuid NOT NULL,
    PRIMARY KEY (team_id, user_id),
    FOREIGN KEY (organization_id, team_id) REFERENCES team (organization_id, id) ON DELETE CASCADE,
    FOREIGN KEY (organization_id, user_id) REFERENCES membership (organization_id, user_id) ON DELETE CASCADE
);
CREATE INDEX team_member_user_idx ON team_member (organization_id, user_id);

CREATE TABLE api_token (
    id              uuid        PRIMARY KEY,
    user_id         uuid        NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    organization_id uuid        REFERENCES organization (id) ON DELETE CASCADE,
    name            text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 80),
    token_prefix    text        NOT NULL,
    token_hash      text        NOT NULL UNIQUE,
    scopes          text[]      NOT NULL CHECK (scopes <@ ARRAY['read', 'write'] AND cardinality(scopes) > 0),
    created_at      timestamptz NOT NULL DEFAULT now(),
    last_used_at    timestamptz,
    expires_at      timestamptz,
    revoked_at      timestamptz
);
CREATE INDEX api_token_user_idx ON api_token (user_id) WHERE revoked_at IS NULL;
