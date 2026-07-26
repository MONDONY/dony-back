package com.dony.api.emailotp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
     * Sans clé, aucun OTP ne peut partir : l'inscription et la connexion par email sont
     * mortes. Démarrer quand même servirait un parcours cassé en silence, exactement le
     * scénario qui a coûté deux jours en local.
     */
    @Test
    void prodProfile_blankKey_refusesToStart() {
        assertThatThrownBy(() -> guard("", "prod").verifyEmailSendingIsConfigured())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resend-api-key");
    }

    @Test
    void prodProfile_nullKey_refusesToStart() {
        assertThatThrownBy(() -> guard(null, "prod").verifyEmailSendingIsConfigured())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void prodProfile_keyConfigured_starts() {
        assertThatCode(() -> guard("re_test_key", "prod").verifyEmailSendingIsConfigured())
                .doesNotThrowAnyException();
    }

    /** Un développeur local n'a pas toujours besoin d'emails réels : avertir, pas bloquer. */
    @Test
    void devProfile_blankKey_startsAnyway() {
        assertThatCode(() -> guard("", "dev").verifyEmailSendingIsConfigured())
                .doesNotThrowAnyException();
    }

    /**
     * Le profil test monte le contexte Spring complet sans clé Resend : si le garde-fou
     * bloquait ici, toute la suite d'intégration tomberait.
     */
    @Test
    void testProfile_blankKey_startsAnyway() {
        assertThatCode(() -> guard("", "test").verifyEmailSendingIsConfigured())
                .doesNotThrowAnyException();
    }

    @Test
    void noActiveProfile_blankKey_startsAnyway() {
        assertThatCode(() -> guard("").verifyEmailSendingIsConfigured())
                .doesNotThrowAnyException();
    }

    /** La valeur par défaut des propriétés est vide : c'est bien ce cas que le garde couvre. */
    @Test
    void defaultPropertiesHaveNoApiKey() {
        assertThat(new EmailOtpProperties().getResendApiKey()).isEmpty();
    }
}
