-- V139__profile_fields.sql
ALTER TABLE users ADD COLUMN bio varchar(280);
ALTER TABLE users ADD COLUMN avatar_url varchar(512);
ALTER TABLE users ADD COLUMN transport_mode varchar(16);

CREATE TABLE user_languages (
    user_id  uuid NOT NULL REFERENCES users(id),
    language varchar(32) NOT NULL,
    PRIMARY KEY (user_id, language)
);
