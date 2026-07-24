package com.dony.api.auth;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.UserRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vérifie les 4 opérations du SDK Admin sur lesquelles repose désormais TOUT le
 * contact (téléphone, email) après le pivot : lecture unitaire, lecture par lot,
 * recherche par email/téléphone, écriture. La suite normale tourne sans bean
 * {@link FirebaseAuth} — {@link FirebaseContactService} y dégrade vers
 * {@code Contact.EMPTY} et ne prouve donc RIEN sur les appels réels au SDK.
 *
 * <p>Marqué {@code @Tag("firebase-live")}, exclu de Surefire par défaut. Il frappe
 * le vrai projet Firebase (dony-36cb2) via le service account de dev et crée un
 * utilisateur Firebase jetable, supprimé en fin de test. À lancer à la demande :
 *
 * <pre>{@code ./mvnw test -Dtest=FirebaseContactLiveIT -Dgroups=firebase-live}</pre>
 *
 * <p>Prérequis : {@code src/main/resources/firebase-service-account.json} présent
 * (dev), ou {@code GOOGLE_APPLICATION_CREDENTIALS} pointant sur un service account.
 */
@Tag("firebase-live")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class) // updateEmail muterait l'user partagé : il passe en dernier
@DisplayName("FirebaseContactService — contre le vrai Firebase (dony-36cb2)")
class FirebaseContactLiveIT {

    private static final Logger log = LoggerFactory.getLogger(FirebaseContactLiveIT.class);

    private FirebaseAuth firebaseAuth;
    private FirebaseContactService service;

    private String uid;
    // Identité jetable, isolée du reste du projet par le nonce dans l'adresse.
    private final String email = "live-it-" + System.nanoTime() + "@dony-live-test.invalid";
    private final String phone = "+1555" + String.format("%07d", System.nanoTime() % 10_000_000);

    @BeforeAll
    void setUp() throws Exception {
        if (FirebaseApp.getApps().isEmpty()) {
            GoogleCredentials creds;
            String envPath = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
            if (envPath != null && !envPath.isBlank()) {
                creds = GoogleCredentials.getApplicationDefault();
            } else {
                try (InputStream in = getClass().getClassLoader()
                        .getResourceAsStream("firebase-service-account.json")) {
                    if (in == null) {
                        throw new IllegalStateException(
                                "firebase-service-account.json absent et GOOGLE_APPLICATION_CREDENTIALS non défini "
                                + "— impossible de lancer le test firebase-live");
                    }
                    creds = GoogleCredentials.fromStream(in);
                }
            }
            FirebaseApp.initializeApp(FirebaseOptions.builder().setCredentials(creds).build());
        }
        firebaseAuth = FirebaseAuth.getInstance();
        service = new FirebaseContactService(firebaseAuth);

        UserRecord created = firebaseAuth.createUser(new UserRecord.CreateRequest()
                .setEmail(email)
                .setPhoneNumber(phone));
        uid = created.getUid();
        log.info("[firebase-live] utilisateur de test créé uid={}", uid);
    }

    @AfterAll
    void tearDown() throws Exception {
        if (uid != null) {
            firebaseAuth.deleteUser(uid);
            log.info("[firebase-live] utilisateur de test supprimé uid={}", uid);
        }
    }

    @Test
    @DisplayName("getUser + cache : lit les coordonnées réelles, mesure la latence à froid vs chaud")
    @Order(1)
    void getContact_readsRealCoordinatesAndCaches() {
        long t0 = System.nanoTime();
        FirebaseContactService.Contact cold = service.getContact(uid);
        long coldMs = (System.nanoTime() - t0) / 1_000_000;

        long t1 = System.nanoTime();
        service.getContact(uid); // servi par le cache
        long warmMs = (System.nanoTime() - t1) / 1_000_000;

        log.info("[firebase-live] getUser latence — froid={}ms chaud={}ms", coldMs, warmMs);

        assertThat(cold.email()).isEqualTo(email);
        assertThat(cold.phoneNumber()).isEqualTo(phone);
        // Le second appel ne frappe pas Firebase : il doit être nettement plus rapide.
        assertThat(warmMs).isLessThan(coldMs);
    }

    @Test
    @DisplayName("getContacts : lecture par lot, alimente le même cache")
    @Order(2)
    void getContacts_batchReadsRealCoordinates() {
        Map<String, FirebaseContactService.Contact> contacts =
                service.getContacts(List.of(uid));

        assertThat(contacts).containsKey(uid);
        assertThat(contacts.get(uid).email()).isEqualTo(email);
        assertThat(contacts.get(uid).phoneNumber()).isEqualTo(phone);
    }

    @Test
    @DisplayName("findUidByEmail / findUidByPhone : résolvent bien l'UID de test")
    @Order(3)
    void findUid_resolvesRealAccount() {
        assertThat(service.findUidByEmail(email)).contains(uid);
        assertThat(service.findUidByPhone(phone)).contains(uid);
        // Adresse inexistante → vide, pas d'exception
        assertThat(service.findUidByEmail("absent-" + System.nanoTime() + "@dony-live-test.invalid"))
                .isEmpty();
    }

    @Test
    @DisplayName("updateEmail : écrit dans Firebase et invalide le cache")
    @Order(4)
    void updateEmail_writesThroughAndEvicts() {
        // Amorce le cache avec l'ancienne valeur
        assertThat(service.getContact(uid).email()).isEqualTo(email);

        String newEmail = "updated-" + System.nanoTime() + "@dony-live-test.invalid";
        service.updateEmail(uid, newEmail);

        // La lecture suivante doit refléter l'écriture (cache invalidé), pas l'ancienne valeur
        assertThat(service.getContact(uid).email()).isEqualTo(newEmail);
    }
}
