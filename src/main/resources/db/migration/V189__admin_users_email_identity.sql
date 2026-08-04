DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM admin_users
        WHERE deleted_at IS NULL
        GROUP BY LOWER(login || '@admin.yadony.invalid')
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'Cannot migrate admin_users: case-insensitive email collision';
    END IF;
END $$;

UPDATE admin_users
SET login = login || '@admin.yadony.invalid';

ALTER TABLE admin_users RENAME COLUMN login TO email;
ALTER TABLE admin_users ALTER COLUMN email TYPE VARCHAR(320);
ALTER TABLE admin_users DROP CONSTRAINT IF EXISTS admin_users_login_key;
CREATE UNIQUE INDEX uq_admin_users_email_lower
    ON admin_users (LOWER(email)) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_admin_users_single_super_admin
    ON admin_users ((role)) WHERE role = 'SUPER_ADMIN' AND deleted_at IS NULL;
