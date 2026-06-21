-- Préférence voyageur : notification temps réel « un colis matche un de mes trajets ».
-- Défaut TRUE : le voyageur reçoit les matchs sauf opt-out explicite via la cloche
-- de l'écran « Colis sur mes trajets ».
ALTER TABLE user_notification_preferences
    ADD COLUMN push_trip_package_match BOOLEAN NOT NULL DEFAULT TRUE;
