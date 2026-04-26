package com.dony.api.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncryptionServiceTest {

    private EncryptionService service;

    @BeforeEach
    void setUp() {
        service = new EncryptionService("test-passphrase-dony-unit-tests");
    }

    @Test
    void encrypt_null_returnsNull() {
        assertThat(service.encrypt(null)).isNull();
    }

    @Test
    void decrypt_null_returnsNull() {
        assertThat(service.decrypt(null)).isNull();
    }

    @Test
    void encryptThenDecrypt_roundTrip() {
        String original = "N°ID: 987654321 — données KYC sensibles";
        String ciphertext = service.encrypt(original);
        assertThat(ciphertext).isNotNull().isNotEqualTo(original);
        assertThat(service.decrypt(ciphertext)).isEqualTo(original);
    }

    @Test
    void encrypt_sameInput_differentCiphertexts_dueToRandomIV() {
        String input = "same-plaintext";
        String c1 = service.encrypt(input);
        String c2 = service.encrypt(input);
        assertThat(c1).isNotEqualTo(c2);
        // But both decrypt to the same value
        assertThat(service.decrypt(c1)).isEqualTo(input);
        assertThat(service.decrypt(c2)).isEqualTo(input);
    }

    @Test
    void decrypt_tampered_throwsIllegalStateException() {
        String ciphertext = service.encrypt("data");
        String tampered = ciphertext.substring(0, ciphertext.length() - 4) + "XXXX";
        assertThatThrownBy(() -> service.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void encryptDecrypt_unicode_preservedCorrectly() {
        String text = "prénom: Αλέξανδρος — Αμαντού 🎉";
        assertThat(service.decrypt(service.encrypt(text))).isEqualTo(text);
    }

    @Test
    void encryptDecrypt_emptyString_roundTrip() {
        assertThat(service.decrypt(service.encrypt(""))).isEqualTo("");
    }
}
