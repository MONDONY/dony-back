package com.yadony.api.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Chiffre/déchiffre une colonne texte au repos via {@link EncryptionService}
 * (AES-256-GCM, IV aléatoire → chiffrement randomisé, non corrélable).
 *
 * À appliquer explicitement avec {@code @Convert(converter =
 * EncryptedStringConverter.class)} sur des champs PII qui ne sont JAMAIS
 * utilisés dans un {@code WHERE}, un {@code ORDER BY} ni une contrainte UNIQUE
 * (le chiffrement randomisé casserait toute recherche/tri/unicité sur la
 * colonne). Pour un champ indexé/lookup (téléphone, email), utiliser une
 * colonne de hash déterministe séparée à la place.
 *
 * {@code null} est préservé (ni chiffré ni déchiffré).
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String attribute) {
        // null ne nécessite aucun chiffrement : on court-circuite avant d'accéder
        // au holder statique (évite de dépendre du contexte Spring pour les
        // valeurs nulles, ex. slices @DataJpaTest persistant une entité sans PII).
        if (attribute == null) {
            return null;
        }
        return EncryptionSupport.encryption().encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        return EncryptionSupport.encryption().decrypt(dbData);
    }
}
