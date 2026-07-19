-- Retire le defaut 'XOF' herite de V112 (spec devise §5.3, correctif bug 3).
-- L'application determine desormais toujours la devise explicitement depuis
-- le wallet (Task 12) -- ce defaut n'est plus qu'un piege latent pour un
-- futur chemin d'insertion qui oublierait de la fixer.
ALTER TABLE mobile_money_payments ALTER COLUMN currency DROP DEFAULT;
