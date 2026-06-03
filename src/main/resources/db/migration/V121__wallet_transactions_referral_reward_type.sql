-- Ajoute le type REFERRAL_REWARD aux transactions wallet.
-- La récompense de parrainage (5€) est désormais créditée sur le wallet dépensable
-- du parrain, en plus de l'entrée user_credits (qui alimente le compteur de l'écran
-- Parrainage). Sans cette mise à jour de la contrainte CHECK, l'INSERT serait rejeté
-- par PostgreSQL.
ALTER TABLE wallet_transactions DROP CONSTRAINT IF EXISTS wallet_transactions_type_check;
ALTER TABLE wallet_transactions ADD CONSTRAINT wallet_transactions_type_check CHECK (
    type IN ('TOP_UP','BID_PAYMENT','COMMISSION_DEDUCTED','REFUND','REFERRAL_REWARD')
);
