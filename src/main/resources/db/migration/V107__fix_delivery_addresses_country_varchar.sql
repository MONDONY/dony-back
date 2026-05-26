-- Fix country column type: CHAR(2) → VARCHAR(2) to match Hibernate @Column(length = 2)
ALTER TABLE delivery_addresses
    ALTER COLUMN country TYPE VARCHAR(2);
