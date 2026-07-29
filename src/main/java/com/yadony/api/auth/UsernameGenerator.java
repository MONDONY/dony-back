package com.yadony.api.auth;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;

/**
 * Fabrique l'identifiant public attribué à un compte à sa création : {@code user} suivi de
 * l'horodatage de création, en secondes depuis l'epoch — par exemple {@code user1785012345}.
 *
 * <p>Les secondes plutôt que les millisecondes : quatorze caractères restent lisibles dans une
 * conversation ou sur une carte d'annonce, dix-sept ne le sont plus. En contrepartie deux comptes
 * créés dans la même seconde produisent la même base, d'où le suffixe aléatoire ci-dessous.
 *
 * <p><b>Unicité.</b> Le test d'existence puis l'insertion ne forment pas une opération atomique :
 * deux inscriptions simultanées peuvent passer le test avec la même valeur. L'index unique
 * {@code ux_users_username} (migration V183) reste donc le garde-fou final, et l'insertion échoue
 * alors plutôt que de produire deux comptes homonymes.
 */
@Component
public class UsernameGenerator {

    /** Au-delà, on laisse l'index unique trancher : dix collisions d'affilée signalent autre chose. */
    private static final int MAX_ATTEMPTS = 10;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final Clock clock;

    // @Autowired explicite : la classe porte deux constructeurs (le second n'existe que pour
    // injecter une horloge figée en test) et Spring, faute de pouvoir trancher, se rabattrait
    // sur un constructeur sans argument qui n'existe pas.
    @org.springframework.beans.factory.annotation.Autowired
    public UsernameGenerator(UserRepository userRepository) {
        this(userRepository, Clock.systemUTC());
    }

    UsernameGenerator(UserRepository userRepository, Clock clock) {
        this.userRepository = userRepository;
        this.clock = clock;
    }

    /**
     * Retourne un username libre au moment de l'appel.
     *
     * <p>Première tentative sans suffixe pour que le cas courant reste propre ; les suivantes
     * ajoutent deux chiffres aléatoires (et non un compteur, qui rendrait la seconde tentative
     * de deux requêtes concurrentes identique).
     */
    public String generate() {
        String base = "user" + Instant.now(clock).getEpochSecond();
        if (!userRepository.existsByUsername(base)) {
            return base;
        }
        for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = base + String.format("%02d", RANDOM.nextInt(100));
            if (!userRepository.existsByUsername(candidate)) {
                return candidate;
            }
        }
        return base + String.format("%02d", RANDOM.nextInt(100));
    }
}
