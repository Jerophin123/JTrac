-- V6: notifications and preferences, saved views, dashboards, webhooks.

CREATE TABLE notification (
    id              uuid        PRIMARY KEY,
    organization_id uuid        NOT NULL,
    recipient_id    uuid        NOT NULL,
    kind            text        NOT NULL,
    issue_id        uuid,
    actor_id        uuid        REFERENCES user_account (id) ON DELETE SET NULL,
    title           text        NOT NULL,
    body            text,
    link            text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    read_at         timestamptz,
    FOREIGN KEY (organization_id, recipient_id) REFERENCES membership (organization_id, user_id) ON DELETE CASCADE,
    FOREIGN KEY (organization_id, issue_id) REFERENCES issue (organization_id, id) ON DELETE CASCADE
);
CREATE INDEX notification_recipient_idx ON notification (recipient_id, created_at DESC);
CREATE INDEX notification_unread_idx ON notification (recipient_id) WHERE read_at IS NULL;

CREATE TABLE notification_preference (
    user_id         uuid    NOT NULL,
    organization_id uuid    NOT NULL,
    kind            text    NOT NULL,
    in_app          boolean NOT NULL,
    email           boolean NOT NULL,
    PRIMARY KEY (user_id, organization_id, kind),
    FOREIGN KEY (organization_id, user_id) REFERENCES membership (organization_id, user_id) ON DELETE CASCADE
);

CREATE TABLE saved_view (
    id              uuid        PRIMARY KEY,
    organization_id uuid        NOT NULL,
    project_id      uuid,
    owner_id        uuid        NOT NULL,
    name            text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 80),
    kind            text        NOT NULL CHECK (kind IN ('LIST', 'BOARD', 'TIMELINE')),
    query           jsonb       NOT NULL DEFAULT '{}'::jsonb,
    display         jsonb       NOT NULL DEFAULT '{}'::jsonb,
    shared          boolean     NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         bigint      NOT NULL DEFAULT 0,
    FOREIGN KEY (organization_id, project_id) REFERENCES project (organization_id, id) ON DELETE CASCADE,
    FOREIGN KEY (organization_id, owner_id) REFERENCES membership (organization_id, user_id) ON DELETE CASCADE
);
CREATE INDEX saved_view_org_idx ON saved_view (organization_id, project_id);

CREATE TABLE dashboard (
    id              uuid        PRIMARY KEY,
    organization_id uuid        NOT NULL,
    owner_id        uuid        NOT NULL,
    name            text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 80),
    shared          boolean     NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         bigint      NOT NULL DEFAULT 0,
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, owner_id) REFERENCES membership (organization_id, user_id) ON DELETE CASCADE
);

CREATE TABLE dashboard_widget (
    id              uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    dashboard_id    uuid NOT NULL,
    kind            text NOT NULL,
    title           text NOT NULL CHECK (char_length(title) BETWEEN 1 AND 80),
    config          jsonb NOT NULL DEFAULT '{}'::jsonb,
    position        int  NOT NULL DEFAULT 0,
    width           int  NOT NULL DEFAULT 1 CHECK (width IN (1, 2)),
    FOREIGN KEY (organization_id, dashboard_id) REFERENCES dashboard (organization_id, id) ON DELETE CASCADE
);
CREATE INDEX dashboard_widget_dashboard_idx ON dashboard_widget (dashboard_id, position);

CREATE TABLE webhook (
    id                uuid        PRIMARY KEY,
    organization_id   uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    project_id        uuid,
    url               text        NOT NULL CHECK (char_length(url) BETWEEN 8 AND 2000),
    description       text        CHECK (char_length(description) <= 200),
    secret_ciphertext text        NOT NULL,
    events            text[]      NOT NULL CHECK (cardinality(events) > 0),
    active            boolean     NOT NULL DEFAULT true,
    created_by        uuid        REFERENCES user_account (id) ON DELETE SET NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    version           bigint      NOT NULL DEFAULT 0,
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, project_id) REFERENCES project (organization_id, id) ON DELETE CASCADE
);

CREATE TABLE webhook_delivery (
    id               uuid        PRIMARY KEY,
    organization_id  uuid        NOT NULL,
    webhook_id       uuid        NOT NULL,
    event_id         uuid        NOT NULL,
    event_type       text        NOT NULL,
    payload          jsonb       NOT NULL,
    status           text        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    attempts         int         NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz NOT NULL DEFAULT now(),
    last_status_code int,
    last_error       text,
    last_duration_ms int,
    created_at       timestamptz NOT NULL DEFAULT now(),
    delivered_at     timestamptz,
    UNIQUE (webhook_id, event_id),
    FOREIGN KEY (organization_id, webhook_id) REFERENCES webhook (organization_id, id) ON DELETE CASCADE
);
CREATE INDEX webhook_delivery_pending_idx ON webhook_delivery (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX webhook_delivery_webhook_idx ON webhook_delivery (webhook_id, created_at DESC);
