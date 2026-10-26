-- Consent records (India's Digital Personal Data Protection Act, 2023, and similar laws):
-- which version of the Terms of Service and Privacy Notice each person accepted, when,
-- and whether they later withdrew consent. Rows are only appended or marked withdrawn,
-- never rewritten, so the history shows what each person agreed to and when.
CREATE TABLE user_consent (
    id           uuid        PRIMARY KEY,
    user_id      uuid        NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    version      text        NOT NULL,
    given_at     timestamptz NOT NULL DEFAULT now(),
    withdrawn_at timestamptz,
    CONSTRAINT user_consent_version_ck CHECK (char_length(version) BETWEEN 1 AND 40),
    CONSTRAINT user_consent_withdrawn_ck CHECK (withdrawn_at IS NULL OR withdrawn_at >= given_at)
);

CREATE INDEX user_consent_user_idx ON user_consent (user_id, given_at DESC);

COMMENT ON TABLE user_consent IS 'Consent to the Terms of Service and Privacy Notice, one row per acceptance.';
COMMENT ON COLUMN user_consent.version IS 'jtac.legal.version in force when consent was given.';
