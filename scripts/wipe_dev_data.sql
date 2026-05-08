-- wipe_dev_data.sql
-- Vide toutes les données métier (annonces, bids, paiements, tracking, etc.) en
-- préservant la table users et user_roles (identités Firebase + rôles).
--
-- Le schéma reste intact : seules les lignes sont supprimées.
-- TRUNCATE bypasse le trigger d'immutabilité de audit_log (il ne déclenche
-- que sur UPDATE/DELETE FOR EACH ROW). RESTART IDENTITY remet à zéro les
-- éventuelles séquences. CASCADE résout l'ordre des FK automatiquement.
--
-- Usage :
--   docker exec -i dony_db psql -U dony -d dony_dev < scripts/wipe_dev_data.sql

BEGIN;

TRUNCATE TABLE
    -- événements / dépendances "feuilles"
    public.tracking_events,
    public.notifications,
    public.audit_log,
    public.admin_alerts,
    public.ratings,
    public.disputes,
    public.cancellations,
    public.rematch_suggestions,

    -- paiements
    public.payments,

    -- bids et leurs tables associatives
    public.bids,

    -- annonces et leurs listes de types
    public.announcement_accepted_types,
    public.announcement_refused_types,
    public.announcements,

    -- messagerie
    public.conversations,

    -- KYC (data sensible, photos, etc.)
    kyc_schema.kyc_verifications
RESTART IDENTITY CASCADE;

COMMIT;

-- Sanity check : combien de lignes restent dans chaque table métier ?
SELECT 'announcements'  AS t, COUNT(*) FROM public.announcements
UNION ALL SELECT 'bids',           COUNT(*) FROM public.bids
UNION ALL SELECT 'payments',       COUNT(*) FROM public.payments
UNION ALL SELECT 'tracking_events',COUNT(*) FROM public.tracking_events
UNION ALL SELECT 'notifications',  COUNT(*) FROM public.notifications
UNION ALL SELECT 'audit_log',      COUNT(*) FROM public.audit_log
UNION ALL SELECT 'cancellations',  COUNT(*) FROM public.cancellations
UNION ALL SELECT 'disputes',       COUNT(*) FROM public.disputes
UNION ALL SELECT 'ratings',        COUNT(*) FROM public.ratings
UNION ALL SELECT 'conversations',  COUNT(*) FROM public.conversations
UNION ALL SELECT 'kyc_verif',      COUNT(*) FROM kyc_schema.kyc_verifications
UNION ALL SELECT 'users (kept)',   COUNT(*) FROM public.users
UNION ALL SELECT 'user_roles (kept)', COUNT(*) FROM public.user_roles;
