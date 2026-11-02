-- V11: External identities for Enterprise SSO (OpenID Connect / OAuth 2.0).
--
-- Maps enterprise identity provider identities (Active Directory / Entra ID, Okta,
-- Keycloak, CAS-OIDC proxies, Google Workspace) to local user accounts.
-- Users signing in via SSO are linked by provider and subject ID (sub claim).

CREATE TABLE user_external_identity (
    id            uuid        PRIMARY KEY,
    user_id       uuid        NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    provider      text        NOT NULL,
    subject_id    text        NOT NULL,
    email         text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    last_login_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT user_external_identity_uq UNIQUE (provider, subject_id),
    CONSTRAINT user_external_identity_provider_ck CHECK (char_length(provider) BETWEEN 1 AND 50),
    CONSTRAINT user_external_identity_subject_ck CHECK (char_length(subject_id) BETWEEN 1 AND 255),
    CONSTRAINT user_external_identity_email_ck CHECK (char_length(email) BETWEEN 3 AND 320)
);

CREATE INDEX user_external_identity_user_idx ON user_external_identity (user_id);

COMMENT ON TABLE user_external_identity IS 'Enterprise identity provider credentials mapped to local user accounts.';
COMMENT ON COLUMN user_external_identity.provider IS 'Identity provider registration key (e.g. oidc, entra, okta).';
COMMENT ON COLUMN user_external_identity.subject_id IS 'Immutable unique identifier (sub claim) issued by the identity provider.';

