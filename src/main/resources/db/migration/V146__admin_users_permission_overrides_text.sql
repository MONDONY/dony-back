-- Change permission_overrides from JSONB to TEXT for JDBC compatibility.
-- The PermissionOverridesConverter serializes Map<String,Boolean> as a JSON string;
-- PostgreSQL JDBC rejects VARCHAR binding into a JSONB column without explicit cast.
ALTER TABLE admin_users
    ALTER COLUMN permission_overrides TYPE TEXT USING permission_overrides::text;
