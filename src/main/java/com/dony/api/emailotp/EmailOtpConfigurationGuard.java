package com.dony.api.emailotp;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Rend visible, au démarrage et en continu, le fait que l'envoi d'emails n'est pas configuré.
 *
 * <p>Sans ce contrôle, une clé Resend absente ne se voit nulle part : la clé vide produit un
 * en-tête {@code Authorization: Bearer } que Resend rejette en 401, {@link ResendEmailService}
 * attrape l'erreur, et en profil {@code dev} l'avale volontairement pour permettre de se
 * connecter en local sans domaine vérifié. L'API répond alors 200 et l'application affiche
 * « Code envoyé à… » alors qu'aucun email n'est parti. La panne est passée inaperçue deux
 * jours, la variable ayant simplement perdu son {@code export} dans le fichier d'environnement.
 *
 * <p><b>Pourquoi ne pas refuser de démarrer.</b> C'était la première version de cette classe.
 * Mais l'authentification principale de Yadony est Firebase Phone : l'email n'est qu'un canal
 * parmi d'autres, et arrêter toute l'API parce qu'un canal secondaire est mal configuré
 * transforme une panne partielle en indisponibilité totale. Les secrets de production vivent
 * dans un {@code .env} sur l'hôte, invisible depuis le dépôt : impossible de garantir avant
 * déploiement que la clé y figure. On signale donc fort, sans jamais bloquer.
 */
@Component("emailOtp")
public class EmailOtpConfigurationGuard implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(EmailOtpConfigurationGuard.class);

    private final EmailOtpProperties properties;
    private final List<String> activeProfiles;

    public EmailOtpConfigurationGuard(EmailOtpProperties properties, Environment environment) {
        this.properties = properties;
        this.activeProfiles = Arrays.asList(environment.getActiveProfiles());
    }

    boolean isEmailSendingConfigured() {
        String key = properties.getResendApiKey();
        return key != null && !key.isBlank();
    }

    @PostConstruct
    void reportConfigurationAtStartup() {
        if (isEmailSendingConfigured()) {
            return;
        }

        if (activeProfiles.contains("prod")) {
            log.error("❌ dony.email.resend-api-key est VIDE en production : aucun code OTP ne "
                    + "partira, l'inscription et la connexion par email sont inutilisables. "
                    + "Renseigner RESEND_API_KEY dans le .env de l'hôte, puis redémarrer.");
            return;
        }

        log.warn("⚠️  dony.email.resend-api-key est VIDE — aucun email ne sera réellement envoyé. "
                + "Les codes OTP apparaîtront uniquement dans ces logs. "
                + "Si ce n'est pas voulu, vérifier que RESEND_API_KEY est bien EXPORTÉE "
                + "(une ligne sans « export » dans .env.dev reste locale au shell et "
                + "n'atteint jamais la JVM).");
    }

    /**
     * Toujours {@code UP}, même sans clé — et c'est délibéré.
     *
     * <p>{@code docker-compose.prod.yml} sonde {@code /actuator/health} et le conteneur est en
     * {@code restart: unless-stopped}. Un statut {@code DOWN} ferait échouer la sonde et
     * mettrait le conteneur en boucle de redémarrage : exactement l'indisponibilité que cette
     * classe cherche à éviter. Le défaut de configuration se lit donc dans le détail
     * {@code resendApiKey}, sur lequel une alerte peut porter sans risquer la production.
     */
    @Override
    public Health health() {
        return Health.up()
                .withDetail("resendApiKey", isEmailSendingConfigured() ? "configured" : "MISSING")
                .build();
    }
}
