package com.yadony.api.emailotp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("EmailOtpConfigurationGuard")
class EmailOtpConfigurationGuardTest {

    private EmailOtpConfigurationGuard guard(String apiKey, String... profiles) {
        EmailOtpProperties props = new EmailOtpProperties();
        props.setResendApiKey(apiKey);
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return new EmailOtpConfigurationGuard(props, env);
    }

    /**
     * Le démarrage ne doit JAMAIS être bloqué, quel que soit le profil.
     *
     * <p>L'authentification principale est Firebase Phone : arrêter toute l'API parce qu'un
     * canal secondaire est mal configuré transformerait une panne partielle en indisponibilité
     * totale. Et les secrets de production vivent dans un {@code .env} sur l'hôte, invisible
     * depuis le dépôt : impossible de garantir avant déploiement que la clé y figure.
     */
    @Test
    void neverBlocksStartup_whateverTheProfile() {
        for (String profile : new String[] {"prod", "staging", "dev", "test"}) {
            assertThatCode(() -> guard("", profile).reportConfigurationAtStartup())
                    .as("profil %s", profile)
                    .doesNotThrowAnyException();
        }
        assertThatCode(() -> guard(null, "prod").reportConfigurationAtStartup())
                .doesNotThrowAnyException();
        assertThatCode(() -> guard("").reportConfigurationAtStartup())
                .doesNotThrowAnyException();
    }

    /**
     * Le statut doit rester UP même sans clé.
     *
     * <p>{@code docker-compose.prod.yml} sonde {@code /actuator/health} et le conteneur est en
     * {@code restart: unless-stopped} : un {@code DOWN} ferait échouer la sonde et mettrait le
     * conteneur en boucle de redémarrage. Ce test existe pour qu'un futur passage à
     * {@code Health.down()} casse ici plutôt qu'en production.
     */
    @Test
    void healthStaysUp_evenWithoutApiKey() {
        assertThat(guard("", "prod").health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void healthReportsMissingKeyInDetails() {
        assertThat(guard("", "prod").health().getDetails())
                .containsEntry("resendApiKey", "MISSING");
    }

    @Test
    void healthReportsConfiguredKeyInDetails() {
        assertThat(guard("re_test_key", "prod").health().getDetails())
                .containsEntry("resendApiKey", "configured");
    }

    @Test
    void blankKeyCountsAsMissing() {
        assertThat(guard("   ", "prod").isEmailSendingConfigured()).isFalse();
        assertThat(guard(null, "prod").isEmailSendingConfigured()).isFalse();
        assertThat(guard("re_test_key", "prod").isEmailSendingConfigured()).isTrue();
    }

    /** La valeur par défaut des propriétés est vide : c'est bien ce cas que le garde couvre. */
    @Test
    void defaultPropertiesHaveNoApiKey() {
        assertThat(new EmailOtpProperties().getResendApiKey()).isEmpty();
    }
}
