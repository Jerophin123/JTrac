-- Server administrators: the only accounts that may create organizations. Everyone else
-- joins an organization by invitation. First-run setup makes its account one; the
-- operator can grant or revoke it with the user administration command.
ALTER TABLE user_account ADD COLUMN server_admin boolean NOT NULL DEFAULT false;

-- Existing installations: the first account is the one first-run setup (or the operator's
-- createUser command) created, so it keeps the right to create organizations.
UPDATE user_account SET server_admin = true
 WHERE id = (SELECT id FROM user_account ORDER BY created_at, id LIMIT 1);

COMMENT ON COLUMN user_account.server_admin IS 'May create organizations (server-wide administration).';
