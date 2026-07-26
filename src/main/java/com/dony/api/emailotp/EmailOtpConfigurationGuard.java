package com.dony.api.emailotp;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Vérifie au démarrage que l'envoi d'emails est réellement configuré.
 *
 * <p>Sans ce contrôle, une clé Resend absente ne se voit nulle part : la clé vide produit un
 * en-tête {@code Authorization: Bearer } que Resend rejette en 401, {@link ResendEmailService}
 * attrape l'erreur, et en profil {@code dev} l'avale volontairement pour permettre de se
 * connecter en local sans domaine vérifié. L'API répond alors 200 et l'application affiche
 * « Code envoyé à… » alors qu'aucun email n'est parti. La panne est passée inaperçue deux
 * jours, la variable ayant simplement perdu son {@code export} dans le fichier d'environnement.
 *
 * <p>En production, l'absence de clé est fatale : sans email, personne ne peut plus créer de
 * compte ni s'authentifier par adresse. Mieux vaut refuser de démarrer que servir un
 * parcours d'inscription cassé. Ailleurs, un avertissement suffit — un développeur local n'a
 * pas toujours besoin d'emails réels.
 */
@Component
public class EmailOtpConfigurationGuard {

    private static final Logger log = LoggerFactory.getLogger(EmailOtpConfigurationGuard.class);

    private final EmailOtpProperties properties;
    private final List<String> activeProfiles;

    public EmailOtpConfigurationGuard(EmailOtpProperties properties, Environment environment) {
        this.properties = properties;
        this.activeProfiles = Arrays.asList(environment.getActiveProfiles());
    }

    @PostConstruct
    void verifyEmailSendingIsConfigured() {
        if (properties.getResendApiKey() != null && !properties.getResendApiKey().isBlank()) {
            return;
        }

        if (activeProfiles.contains("prod")) {
            throw new IllegalStateException(
                    "dony.email.resend-api-key est vide : aucun code OTP ne pourrait être envoyé, "
                            + "l'inscription et la connexion par email seraient inutilisables. "
                            + "Renseigner RESEND_API_KEY avant de démarrer en production.");
        }

        log.warn("⚠️  dony.email.resend-api-key est VIDE — aucun email ne sera réellement envoyé. "
                + "Les codes OTP apparaîtront uniquement dans ces logs. "
                + "Si ce n'est pas voulu, vérifier que RESEND_API_KEY est bien EXPORTÉE "
                + "(une ligne sans « export » dans .env.dev reste locale au shell et "
                + "n'atteint jamais la JVM).");
    }
}
