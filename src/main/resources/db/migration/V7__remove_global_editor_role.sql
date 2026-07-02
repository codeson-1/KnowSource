UPDATE users
SET global_role = 'VIEWER',
    token_version = token_version + 1
WHERE global_role = 'EDITOR';

ALTER TABLE users
DROP CONSTRAINT IF EXISTS chk_users_global_role;

ALTER TABLE users
ADD CONSTRAINT chk_users_global_role
CHECK (global_role IN ('ADMIN', 'VIEWER'));
