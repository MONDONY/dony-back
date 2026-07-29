package com.yadony.api.common;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EncryptedStringConverter — chiffrement de colonne au repos")
class EncryptedStringConverterTest {

    private final EncryptedStringConverter converter = new EncryptedStringConverter();

    @BeforeAll
    static void initEncryption() {
        // Initialise le holder statique lu par le converter (fait normalement
        // par Spring au démarrage du contexte).
        new EncryptionSupport(new EncryptionService(
                "test-encryption-key-not-for-production-use-32b"));
    }

    @Test
    @DisplayName("round-trip : le clair est restitué après chiffrement/déchiffrement")
    void roundTrip() {
        String plain = "+221701234567";
        String db = converter.convertToDatabaseColumn(plain);

        assertThat(db).isNotNull().isNotEqualTo(plain);       // stocké chiffré
        assertThat(converter.convertToEntityAttribute(db)).isEqualTo(plain); // lu en clair
    }

    @Test
    @DisplayName("chiffrement randomisé : deux chiffrements du même clair diffèrent (IV aléatoire, non corrélable)")
    void randomizedCiphertext() {
        String plain = "Awa Diop";
        String a = converter.convertToDatabaseColumn(plain);
        String b = converter.convertToDatabaseColumn(plain);

        assertThat(a).isNotEqualTo(b);
        assertThat(converter.convertToEntityAttribute(a)).isEqualTo(plain);
        assertThat(converter.convertToEntityAttribute(b)).isEqualTo(plain);
    }

    @Test
    @DisplayName("null préservé dans les deux sens")
    void nullPassthrough() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
