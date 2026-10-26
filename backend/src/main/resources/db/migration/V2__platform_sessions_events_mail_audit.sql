-- V2: platform infrastructure — persistent sessions, reliable event publication,
-- account tokens, transactional email outbox and the audit log.
-- Runs as the schema owner; default privileges grant DML to ${appRole}.

-- Spring Session JDBC (schema-postgresql.sql from spring-session-jdbc 4.1.1).
CREATE TABLE spring_session (
    primary_id            char(36)     NOT NULL,
    session_id            char(36)     NOT NULL,
    creation_time         bigint       NOT NULL,
    last_access_time      bigint       NOT NULL,
    max_inactive_interval int          NOT NULL,
    expiry_time           bigint       NOT NULL,
    principal_name        varchar(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);
CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id char(36)     NOT NULL,
    attribute_name     varchar(200) NOT NULL,
    attribute_bytes    bytea        NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES spring_session (primary_id) ON DELETE CASCADE
);

-- Spring Modulith event publication registry (schemas/v2/schema-postgresql.sql from
-- spring-modulith-events-jdbc 2.1.2): at-least-once delivery of domain events to
-- listeners, resubmitted after a crash.
CREATE TABLE event_publication (
    id                     uuid                     NOT NULL,
    listener_id            text                     NOT NULL,
    event_type             text                     NOT NULL,
    serialized_event       text                     NOT NULL,
    publication_date       timestamp with time zone NOT NULL,
    completion_date        timestamp with time zone,
    status                 text,
    completion_attempts    int,
    last_resubmission_date timestamp with time zone,
    PRIMARY KEY (id)
);
CREATE INDEX event_publication_serialized_event_hash_idx ON event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON event_publication (completion_date);

-- User handles (for @mentions) and email verification.
ALTER TABLE user_account ADD COLUMN handle text;
ALTER TABLE user_account ADD COLUMN email_verified_at timestamptz;
UPDATE user_account
   SET handle = coalesce(nullif(left(regexp_replace(regexp_replace(lower(split_part(email, '@', 1)),
                    '[^a-z0-9_.-]', '', 'g'), '^[^a-z0-9]+', ''), 20), ''), 'user')
                || '-' || left(replace(id::text, '-', ''), 6),
       email_verified_at = created_at;   -- accounts created before V2 were administrator-provisioned
ALTER TABLE user_account ALTER COLUMN handle SET NOT NULL;
ALTER TABLE user_account ADD CONSTRAINT user_account_handle_ck CHECK (handle ~ '^[a-z0-9][a-z0-9_.-]{1,31}$');
CREATE UNIQUE INDEX user_account_handle_uk ON user_account (handle);

-- One-time account tokens (password reset, email verification). Only a SHA-256 hash of
-- the token is stored; the token itself exists only in the email sent to the user.
CREATE TABLE account_token (
    id           uuid        PRIMARY KEY,
    user_id      uuid        NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    purpose      text        NOT NULL CHECK (purpose IN ('PASSWORD_RESET', 'EMAIL_VERIFICATION')),
    token_hash   text        NOT NULL UNIQUE,
    expires_at   timestamptz NOT NULL,
    consumed_at  timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX account_token_user_idx ON account_token (user_id, purpose);

-- Transactional email outbox: rows are written in the business transaction and
-- delivered by a background worker with retries (SELECT ... FOR UPDATE SKIP LOCKED).
CREATE TABLE email_outbox (
    id              uuid        PRIMARY KEY,
    to_address      text        NOT NULL,
    subject         text        NOT NULL,
    text_body       text        NOT NULL,
    html_body       text,
    status          text        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts        int         NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error      text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    sent_at         timestamptz
);
CREATE INDEX email_outbox_pending_idx ON email_outbox (next_attempt_at) WHERE status = 'PENDING';

-- Audit log of security-relevant actions. organization_id is NULL for platform-level
-- events (e.g. sign-in). Append-only for the application role.
CREATE TABLE audit_event (
    id              uuid        PRIMARY KEY,
    organization_id uuid,
    actor_id        uuid,
    action          text        NOT NULL,
    target_type     text,
    target_id       text,
    details         jsonb       NOT NULL DEFAULT '{}'::jsonb,
    client_address  text,
    occurred_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_event_org_time_idx ON audit_event (organization_id, occurred_at DESC);
CREATE INDEX audit_event_actor_idx ON audit_event (actor_id, occurred_at DESC);
REVOKE UPDATE, DELETE, TRUNCATE ON audit_event FROM ${appRole};
