package com.yadony.api.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UsernameGenerator")
class UsernameGeneratorTest {

    @Mock private UserRepository userRepository;

    /** 2026-07-26T12:00:00Z → 1785153600 s. */
    private static final Instant FIXED = Instant.parse("2026-07-26T12:00:00Z");

    private UsernameGenerator generator() {
        return new UsernameGenerator(userRepository, Clock.fixed(FIXED, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("username libre → « user » + horodatage en secondes, sans suffixe")
    void generate_freeUsername_usesEpochSecondsWithoutSuffix() {
        when(userRepository.existsByUsername(anyString())).thenReturn(false);

        assertThat(generator().generate()).isEqualTo("user" + FIXED.getEpochSecond());
    }

    @Test
    @DisplayName("préfixe « user » et chiffres uniquement")
    void generate_matchesExpectedShape() {
        when(userRepository.existsByUsername(anyString())).thenReturn(false);

        assertThat(generator().generate()).matches("^user\\d+$");
    }

    /**
     * Deux comptes créés dans la même seconde partagent la base : sans suffixe, l'index unique
     * {@code ux_users_username} ferait échouer la seconde inscription.
     */
    @Test
    @DisplayName("base déjà prise → suffixe ajouté, valeur différente de la base")
    void generate_baseTaken_appendsSuffix() {
        String base = "user" + FIXED.getEpochSecond();
        when(userRepository.existsByUsername(base)).thenReturn(true);
        when(userRepository.existsByUsername(org.mockito.ArgumentMatchers.argThat(
                candidate -> candidate != null && !candidate.equals(base)))).thenReturn(false);

        String generated = generator().generate();

        assertThat(generated).startsWith(base).isNotEqualTo(base).matches("^user\\d+$");
    }

    /**
     * Le générateur ne doit jamais boucler indéfiniment ni lever : passé le plafond de
     * tentatives il rend une valeur, et c'est l'index unique qui tranche à l'insertion.
     */
    @Test
    @DisplayName("toutes les valeurs prises → rend quand même une valeur, sans boucler")
    void generate_everythingTaken_stillReturnsAValue() {
        when(userRepository.existsByUsername(anyString())).thenReturn(true);

        assertThat(generator().generate()).matches("^user\\d+$");
    }

    /** Le suffixe est tiré au hasard, pas incrémenté : deux appels concurrents divergent. */
    @Test
    @DisplayName("appels répétés sur base prise → suffixes variés")
    void generate_repeatedCalls_producesVariedSuffixes() {
        String base = "user" + FIXED.getEpochSecond();
        when(userRepository.existsByUsername(base)).thenReturn(true);
        when(userRepository.existsByUsername(org.mockito.ArgumentMatchers.argThat(
                candidate -> candidate != null && !candidate.equals(base)))).thenReturn(false);

        Set<String> generated = new HashSet<>();
        UsernameGenerator generator = generator();
        for (int i = 0; i < 50; i++) {
            generated.add(generator.generate());
        }

        assertThat(generated).hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("longueur compatible avec VARCHAR(32)")
    void generate_fitsColumnLength() {
        when(userRepository.existsByUsername(anyString())).thenReturn(true);

        assertThat(generator().generate().length()).isLessThanOrEqualTo(32);
    }
}
