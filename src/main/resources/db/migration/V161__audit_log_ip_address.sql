-- V154 : add ip_address to audit_log
-- AuditLogEntity est @Immutable : on ajoute juste la colonne, les nouvelles lignes la rempliront.
ALTER TABLE audit_log
    ADD COLUMN IF NOT EXISTS ip_address VARCHAR(45);
