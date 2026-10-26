-- V1: identity — user accounts.
-- Runs as the schema owner (jtac_owner). Default privileges defined by the DBA give the
-- application role (${appRole}) SELECT/INSERT/UPDATE/DELETE on tables created here.

CREATE TABLE user_account (
    id               uuid        PRIMARY KEY DEFAULT uuidv7(),
    email            text        NOT NULL,
    email_normalized text        GENERATED ALWAYS AS (lower(btrim(email))) STORED,
    display_name     text        NOT NULL,
    password_hash    text        NOT NULL,
    status           text        NOT NULL DEFAULT 'ACTIVE',
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    last_login_at    timestamptz,
    version          bigint      NOT NULL DEFAULT 0,
    CONSTRAINT user_account_status_ck       CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT user_account_email_ck        CHECK (char_length(email) BETWEEN 3 AND 320),
    CONSTRAINT user_account_display_name_ck CHECK (char_length(display_name) BETWEEN 1 AND 120)
);

CREATE UNIQUE INDEX user_account_email_normalized_uk ON user_account (email_normalized);

COMMENT ON TABLE user_account IS 'Global user identities. Organization membership is modelled separately (M2).';
COMMENT ON COLUMN user_account.password_hash IS 'Spring Security DelegatingPasswordEncoder format, e.g. {bcrypt}...';

-- The migration history must only be written by the schema owner. The default
-- privileges granted DML on it to the application role; keep read access (needed for
-- startup validation) and revoke the rest.
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON flyway_schema_history FROM ${appRole};
