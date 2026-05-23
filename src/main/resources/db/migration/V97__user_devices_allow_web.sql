-- Étend la contrainte CHECK platform pour accepter 'web' (dony-pro)
ALTER TABLE user_devices DROP CONSTRAINT IF EXISTS user_devices_platform_check;
ALTER TABLE user_devices ADD CONSTRAINT user_devices_platform_check
    CHECK (platform IN ('ios', 'android', 'web'));
