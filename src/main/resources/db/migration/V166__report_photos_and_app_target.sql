-- Incidents signalés depuis l'app avec captures d'écran.
-- 1) target_id devient nullable : un incident APP (bug, problème de paiement…)
--    ne cible pas forcément une entité précise.
ALTER TABLE reports ALTER COLUMN target_id DROP NOT NULL;

-- 2) Photos jointes à un signalement (max 4, appliqué côté service).
CREATE TABLE report_photos (
    id         UUID         NOT NULL DEFAULT gen_random_uuid(),
    report_id  UUID         NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_report_photos PRIMARY KEY (id),
    CONSTRAINT fk_report_photos_report FOREIGN KEY (report_id) REFERENCES reports (id)
);
CREATE INDEX idx_report_photos_report ON report_photos (report_id);
