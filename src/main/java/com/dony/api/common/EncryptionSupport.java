package com.dony.api.common;

import org.springframework.stereotype.Component;

/**
 * Pont entre le conteneur Spring et {@link EncryptedStringConverter}.
 *
 * Les {@code AttributeConverter} JPA sont instanciés par Hibernate (pas par
 * Spring), donc on ne peut pas y injecter {@link EncryptionService} directement.
 * Ce composant Spring capture le service dans un champ statique au démarrage du
 * contexte (avant toute requête Hibernate), et le converter le lit via
 * {@link #encryption()}.
 */
@Component
public class EncryptionSupport {

    private static volatile EncryptionService encryptionService;

    public EncryptionSupport(EncryptionService encryptionService) {
        EncryptionSupport.encryptionService = encryptionService;
    }

    static EncryptionService encryption() {
        EncryptionService service = encryptionService;
        if (service == null) {
            throw new IllegalStateException(
                    "EncryptionService non initialisé : le contexte Spring doit être démarré "
                            + "avant toute conversion JPA chiffrée");
        }
        return service;
    }
}
