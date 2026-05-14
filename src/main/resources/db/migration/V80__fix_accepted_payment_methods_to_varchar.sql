-- Conversion TEXT[] → VARCHAR(255) pour compatibilité avec
-- PaymentMethodSetConverter (AttributeConverter<Set<PaymentMethod>, String>).
-- Hibernate schema-validation échoue si la colonne est de type _text (ARRAY).

ALTER TABLE announcements DROP CONSTRAINT IF EXISTS chk_announcements_payment_methods;

ALTER TABLE announcements
    ALTER COLUMN accepted_payment_methods TYPE VARCHAR(255)
    USING array_to_string(accepted_payment_methods, ',');

ALTER TABLE announcements
    ALTER COLUMN accepted_payment_methods SET DEFAULT 'STRIPE';
