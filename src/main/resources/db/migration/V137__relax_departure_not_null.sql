-- V137 : relâche les contraintes NOT NULL ajoutées en V134 sur departure_time/departure_at.
-- L'obligation de l'heure de départ est portée au niveau applicatif (@NotNull sur
-- AnnouncementRequest.departureTime) ; departure_at est un instant canonique best-effort
-- (calculé quand l'heure est présente). Ce relâchement évite de casser les flux/fixtures
-- qui persistent une annonce sans heure et garde le verrou d'annulation tolérant au null.
ALTER TABLE announcements ALTER COLUMN departure_time DROP NOT NULL;
ALTER TABLE announcements ALTER COLUMN departure_at  DROP NOT NULL;
