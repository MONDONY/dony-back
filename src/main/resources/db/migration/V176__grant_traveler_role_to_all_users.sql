-- Voyageur universel : tout utilisateur porte le rôle TRAVELER.
-- Le gate réel (KYC à la publication, Stripe pour la capacité carte)
-- est appliqué au moment de l'action, plus au niveau du rôle.
INSERT INTO user_roles (user_id, role)
SELECT id, 'TRAVELER'
FROM users
ON CONFLICT (user_id, role) DO NOTHING;
