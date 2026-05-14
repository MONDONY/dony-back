-- V60__package_requests_disclaimer_ip_varchar.sql
-- Hibernate binds String params via setString (VARCHAR) and PostgreSQL refuses
-- the implicit cast to INET. Use VARCHAR(45) which fits both IPv4 (15) and IPv6 (39).
ALTER TABLE package_requests
    ALTER COLUMN disclaimer_signed_ip TYPE VARCHAR(45)
    USING disclaimer_signed_ip::text;
