-- V5: issues, labels, links, watchers, activity history, comments, attachments.

CREATE TABLE issue (
    id              uuid          PRIMARY KEY,
    organization_id uuid          NOT NULL,
    project_id      uuid          NOT NULL,
    number          bigint        NOT NULL CHECK (number > 0),
    type_id         uuid          NOT NULL,
    title           text          NOT NULL CHECK (char_length(title) BETWEEN 1 AND 255),
    description     text          CHECK (char_length(description) <= 100000),
    status_id       uuid          NOT NULL,
    priority        text          NOT NULL DEFAULT 'NONE' CHECK (priority IN ('URGENT', 'HIGH', 'MEDIUM', 'LOW', 'NONE')),
    assignee_id     uuid,
    reporter_id     uuid          NOT NULL REFERENCES user_account (id),
    parent_id       uuid,
    milestone_id    uuid,
    start_date      date,
    due_date        date,
    estimate        numeric(10, 2) CHECK (estimate IS NULL OR estimate >= 0),
    rank            bigint        NOT NULL,
    custom_values   jsonb         NOT NULL DEFAULT '{}'::jsonb,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    resolved_at     timestamptz,
    version         bigint        NOT NULL DEFAULT 0,
    search_vector   tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', coalesce(title, '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(description, '')), 'B')) STORED,
    UNIQUE (project_id, number),
    UNIQUE (organization_id, id),
    UNIQUE (project_id, id),
    CHECK (start_date IS NULL OR due_date IS NULL OR start_date <= due_date),
    CHECK (parent_id IS NULL OR parent_id <> id),
    FOREIGN KEY (organization_id, project_id) REFERENCES project (organization_id, id) ON DELETE CASCADE,
    FOREIGN KEY (project_id, type_id) REFERENCES issue_type (project_id, id),
    FOREIGN KEY (project_id, status_id) REFERENCES status (project_id, id),
    FOREIGN KEY (organization_id, assignee_id) REFERENCES membership (organization_id, user_id)
        ON DELETE SET NULL (assignee_id),
    FOREIGN KEY (project_id, parent_id) REFERENCES issue (project_id, id) ON DELETE CASCADE,
    FOREIGN KEY (project_id, milestone_id) REFERENCES milestone (project_id, id) ON DELETE SET NULL (milestone_id)
);
CREATE INDEX issue_project_status_idx ON issue (project_id, status_id);
CREATE INDEX issue_project_rank_idx ON issue (project_id, rank);
CREATE INDEX issue_project_updated_idx ON issue (project_id, updated_at DESC);
CREATE INDEX issue_assignee_idx ON issue (organization_id, assignee_id);
CREATE INDEX issue_reporter_idx ON issue (organization_id, reporter_id);
CREATE INDEX issue_parent_idx ON issue (parent_id);
CREATE INDEX issue_milestone_idx ON issue (milestone_id);
CREATE INDEX issue_due_idx ON issue (project_id, due_date);
CREATE INDEX issue_search_idx ON issue USING gin (search_vector);
CREATE INDEX issue_custom_values_idx ON issue USING gin (custom_values jsonb_path_ops);

CREATE TABLE issue_label (
    issue_id   uuid NOT NULL,
    project_id uuid NOT NULL,
    label_id   uuid NOT NULL,
    PRIMARY KEY (issue_id, label_id),
    FOREIGN KEY (project_id, issue_id) REFERENCES issue (project_id, id) ON DELETE CASCADE,
    FOREIGN KEY (project_id, label_id) REFERENCES label (project_id, id) ON DELETE CASCADE
);
CREATE INDEX issue_label_label_idx ON issue_label (label_id);

CREATE TABLE issue_link (
    id              uuid        PRIMARY KEY,
    organization_id uuid        NOT NULL,
    source_id       uuid        NOT NULL,
    target_id       uuid        NOT NULL,
    type            text        NOT NULL CHECK (type IN ('BLOCKS', 'RELATES', 'DUPLICATES')),
    created_by      uuid        REFERENCES user_account (id) ON DELETE SET NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CHECK (source_id <> target_id),
    UNIQUE (source_id, target_id, type),
    FOREIGN KEY (organization_id, source_id) REFERENCES issue (organization_id, id) ON DELETE CASCADE,
    FOREIGN KEY (organization_id, target_id) REFERENCES issue (organization_id, id) ON DELETE CASCADE
);
CREATE INDEX issue_link_target_idx ON issue_link (target_id);

CREATE TABLE issue_watcher (
    issue_id        uuid NOT NULL,
    organization_id uuid NOT NULL,
    user_id         uuid NOT NULL,
    PRIMARY KEY (issue_id, user_id),
    FOREIGN KEY (organization_id, issue_id) REFERENCES issue (organization_id, id) ON DELETE CASCADE,
    FOREIGN KEY (organization_id, user_id) REFERENCES membership (organization_id, user_id) ON DELETE CASCADE
);
CREATE INDEX issue_watcher_user_idx ON issue_watcher (organization_id, user_id);

-- Append-only, attributed change history (one row per changed field / event).
CREATE TABLE issue_activity (
    id              uuid        PRIMARY KEY,
    organization_id uuid        NOT NULL,
    issue_id        uuid        NOT NULL,
    actor_id        uuid        REFERENCES user_account (id) ON DELETE SET NULL,
    kind            text        NOT NULL,
    field           text,
    old_value       jsonb,
    new_value       jsonb,
    created_at      timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (organization_id, issue_id) REFERENCES issue (organization_id, id) ON DELETE CASCADE
);
CREATE INDEX issue_activity_issue_idx ON issue_activity (issue_id, created_at);
CREATE INDEX issue_activity_org_time_idx ON issue_activity (organization_id, created_at DESC);

CREATE TABLE comment (
    id              uuid        PRIMARY KEY,
    organization_id uuid        NOT NULL,
    issue_id        uuid        NOT NULL,
    author_id       uuid        REFERENCES user_account (id) ON DELETE SET NULL,
    body            text        NOT NULL CHECK (char_length(body) BETWEEN 1 AND 50000),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    edited_at       timestamptz,
    deleted_at      timestamptz,
    version         bigint      NOT NULL DEFAULT 0,
    FOREIGN KEY (organization_id, issue_id) REFERENCES issue (organization_id, id) ON DELETE CASCADE
);
CREATE INDEX comment_issue_idx ON comment (issue_id, created_at);

CREATE TABLE attachment (
    id              uuid        PRIMARY KEY,
    organization_id uuid        NOT NULL,
    issue_id        uuid        NOT NULL,
    uploader_id     uuid        REFERENCES user_account (id) ON DELETE SET NULL,
    file_name       text        NOT NULL CHECK (char_length(file_name) BETWEEN 1 AND 255),
    content_type    text        NOT NULL,
    size_bytes      bigint      NOT NULL CHECK (size_bytes >= 0),
    sha256          text        NOT NULL,
    storage_key     text        NOT NULL UNIQUE,
    created_at      timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (organization_id, issue_id) REFERENCES issue (organization_id, id) ON DELETE CASCADE
);
CREATE INDEX attachment_issue_idx ON attachment (issue_id);
