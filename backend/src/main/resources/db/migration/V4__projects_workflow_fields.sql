-- V4: projects, project roles and membership, issue types, labels, statuses,
-- versioned workflows, custom field definitions and milestones.

CREATE TABLE project (
    id                uuid        PRIMARY KEY,
    organization_id   uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    key               text        NOT NULL CHECK (key ~ '^[A-Z][A-Z0-9]{1,9}$'),
    name              text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 80),
    description       text        CHECK (char_length(description) <= 5000),
    visibility        text        NOT NULL CHECK (visibility IN ('PRIVATE', 'ORGANIZATION')),
    lead_id           uuid        REFERENCES user_account (id) ON DELETE SET NULL,
    next_issue_number bigint      NOT NULL DEFAULT 1 CHECK (next_issue_number > 0),
    archived_at       timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    version           bigint      NOT NULL DEFAULT 0,
    UNIQUE (organization_id, id)
);
CREATE UNIQUE INDEX project_key_uk ON project (organization_id, key);

CREATE TABLE project_role (
    id              uuid    PRIMARY KEY,
    organization_id uuid    NOT NULL,
    project_id      uuid    NOT NULL,
    name            text    NOT NULL CHECK (char_length(name) BETWEEN 1 AND 40),
    permissions     text[]  NOT NULL,
    system_key      text    CHECK (system_key IN ('ADMIN', 'MEMBER', 'VIEWER')),
    position        int     NOT NULL DEFAULT 0,
    UNIQUE (project_id, id),
    FOREIGN KEY (organization_id, project_id) REFERENCES project (organization_id, id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX project_role_name_uk ON project_role (project_id, lower(name));

-- Role granted to every organization member (except guests) of an ORGANIZATION-visible project.
ALTER TABLE project ADD COLUMN default_role_id uuid;
ALTER TABLE project ADD CONSTRAINT project_default_role_fk FOREIGN KEY (id, default_role_id)
    REFERENCES project_role (project_id, id) ON DELETE SET NULL (default_role_id);

CREATE TABLE project_member (
    project_id      uuid        NOT NULL,
    organization_id uuid        NOT NULL,
    user_id         uuid        NOT NULL,
    role_id         uuid        NOT NULL,
    added_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, user_id),
    FOREIGN KEY (organization_id, project_id) REFERENCES project (organization_id, id) ON DELETE CASCADE,
    FOREIGN KEY (organization_id, user_id) REFERENCES membership (organization_id, user_id) ON DELETE CASCADE,
    FOREIGN KEY (project_id, role_id) REFERENCES project_role (project_id, id)
);
CREATE INDEX project_member_user_idx ON project_member (organization_id, user_id);

CREATE TABLE project_team (
    project_id      uuid NOT NULL,
    organization_id uuid NOT NULL,
    team_id         uuid NOT NULL,
    role_id         uuid NOT NULL,
    PRIMARY KEY (project_id, team_id),
    FOREIGN KEY (organization_id, project_id) REFERENCES project (organization_id, id) ON DELETE CASCADE,
    FOREIGN KEY (organization_id, team_id) REFERENCES team (organization_id, id) ON DELETE CASCADE,
    FOREIGN KEY (project_id, role_id) REFERENCES project_role (project_id, id)
);

CREATE TABLE issue_type (
    id              uuid    PRIMARY KEY,
    organization_id uuid    NOT NULL,
    project_id      uuid    NOT NULL,
    name            text    NOT NULL CHECK (char_length(name) BETWEEN 1 AND 40),
    icon            text    NOT NULL DEFAULT 'square' CHECK (icon ~ '^[a-z-]{1,30}$'),
    color           text    NOT NULL DEFAULT '#6c645a' CHECK (color ~ '^#[0-9a-f]{6}$'),
    is_subtask      boolean NOT NULL DEFAULT false,
    position        int     NOT NULL DEFAULT 0,
    archived_at     timestamptz,
    UNIQUE (project_id, id),
    FOREIGN KEY (organization_id, project_id) REFERENCES project (organization_id, id) ON DELETE CASCADE
);

CREATE TABLE label (
    id              uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id      uuid NOT NULL,
    name            text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 40),
    color           text NOT NULL DEFAULT '#6c645a' CHECK (color ~ '^#[0-9a-f]{6}$'),
    UNIQUE (project_id, id),
    FOREIGN KEY (organization_id, project_id) REFERENCES project (organization_id, id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX label_name_uk ON label (project_id, lower(name));

CREATE TABLE status (
    id              uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id      uuid NOT NULL,
    name            text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 40),
    category        text NOT NULL CHECK (category IN ('TODO', 'IN_PROGRESS', 'DONE', 'CANCELED')),
    position        int  NOT NULL DEFAULT 0,
    retired_at      timestamptz,
    UNIQUE (project_id, id),
    FOREIGN KEY (organization_id, project_id) REFERENCES project (organization_id, id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX status_name_uk ON status (project_id, lower(name)) WHERE retired_at IS NULL;

-- A workflow version is an immutable JSON document once published (statuses in use,
-- transitions with role restrictions, field rules). Drafts are edited, then published.
CREATE TABLE workflow_version (
    id              uuid        PRIMARY KEY,
    organization_id uuid        NOT NULL,
    project_id      uuid        NOT NULL,
    version_no      int         NOT NULL CHECK (version_no > 0),
    state           text        NOT NULL CHECK (state IN ('DRAFT', 'PUBLISHED', 'SUPERSEDED')),
    definition      jsonb       NOT NULL,
    created_by      uuid        REFERENCES user_account (id) ON DELETE SET NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    published_by    uuid        REFERENCES user_account (id) ON DELETE SET NULL,
    published_at    timestamptz,
    UNIQUE (project_id, id),
    UNIQUE (project_id, version_no),
    FOREIGN KEY (organization_id, project_id) REFERENCES project (organization_id, id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX workflow_version_one_draft_uk ON workflow_version (project_id) WHERE state = 'DRAFT';
CREATE UNIQUE INDEX workflow_version_one_published_uk ON workflow_version (project_id) WHERE state = 'PUBLISHED';

CREATE TABLE field_definition (
    id              uuid        PRIMARY KEY,
    organization_id uuid        NOT NULL,
    project_id      uuid        NOT NULL,
    key             text        NOT NULL CHECK (key ~ '^[a-z][a-z0-9_]{1,39}$'),
    name            text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 60),
    type            text        NOT NULL CHECK (type IN ('TEXT', 'LONG_TEXT', 'NUMBER', 'DATE', 'SELECT',
                                                         'MULTI_SELECT', 'USER', 'CHECKBOX', 'URL')),
    options         jsonb       NOT NULL DEFAULT '[]'::jsonb,
    description     text        CHECK (char_length(description) <= 500),
    position        int         NOT NULL DEFAULT 0,
    archived_at     timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    version         bigint      NOT NULL DEFAULT 0,
    UNIQUE (project_id, id),
    FOREIGN KEY (organization_id, project_id) REFERENCES project (organization_id, id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX field_definition_key_uk ON field_definition (project_id, key);

CREATE TABLE milestone (
    id              uuid        PRIMARY KEY,
    organization_id uuid        NOT NULL,
    project_id      uuid        NOT NULL,
    name            text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 80),
    description     text        CHECK (char_length(description) <= 5000),
    start_date      date,
    due_date        date,
    state           text        NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN', 'CLOSED')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         bigint      NOT NULL DEFAULT 0,
    UNIQUE (project_id, id),
    CHECK (start_date IS NULL OR due_date IS NULL OR start_date <= due_date),
    FOREIGN KEY (organization_id, project_id) REFERENCES project (organization_id, id) ON DELETE CASCADE
);
