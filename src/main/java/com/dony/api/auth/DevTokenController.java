package com.dony.api.auth;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/dev")
@Profile("dev")
@Tag(name = "Dev", description = "Endpoints de développement uniquement — absents en production")
public class DevTokenController {

    private static final String FIREBASE_TOKEN_URL =
            "https://identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=";

    private final UserRepository userRepository;
    private final FirebaseAuth firebaseAuth;
    private final RestClient restClient;
    private final String firebaseWebApiKey;

    public DevTokenController(
            UserRepository userRepository,
            FirebaseAuth firebaseAuth,
            @Value("${firebase.web-api-key:}") String firebaseWebApiKey) {
        this.userRepository = userRepository;
        this.firebaseAuth = firebaseAuth;
        this.firebaseWebApiKey = firebaseWebApiKey;
        this.restClient = RestClient.create();
    }

    @GetMapping("/token")
    @Transactional
    @Operation(
        summary = "Générer un token Firebase de test",
        description = """
            Crée (si inexistant) un utilisateur de test en base, génère un Firebase ID token prêt à coller dans le champ **Authorize** de Swagger UI.

            **Pré-requis :** définir `FIREBASE_WEB_API_KEY` dans `.env.dev` (Web API Key du projet Firebase, disponible dans Project Settings > General).

            | role | uid utilisé | rôles DB |
            |------|------------|---------|
            | SENDER | dev-test-sender | SENDER |
            | TRAVELER | dev-test-traveler | TRAVELER |
            | ADMIN | dev-test-admin | SENDER + TRAVELER + ADMIN |
            """
    )
    public Map<String, String> getDevToken(
            @RequestParam(defaultValue = "SENDER") Role role) throws FirebaseAuthException {

        if (firebaseAuth == null) {
            throw new IllegalStateException(
                    "Firebase non initialisé — vérifiez firebase.service-account-path dans application-dev.yml");
        }
        if (firebaseWebApiKey.isBlank()) {
            throw new IllegalStateException(
                    "Variable FIREBASE_WEB_API_KEY manquante dans .env.dev (Web API Key Firebase console > Project Settings > General)");
        }

        String uid = "dev-test-" + role.name().toLowerCase();
        ensureTestUserExists(uid, role);

        String customToken = firebaseAuth.createCustomToken(uid);
        String idToken = exchangeForIdToken(customToken);

        return Map.of(
                "idToken", idToken,
                "uid", uid,
                "role", role.name(),
                "usage", "Coller 'idToken' dans le champ Authorize de Swagger UI (sans le préfixe 'Bearer ')"
        );
    }

    private void ensureTestUserExists(String uid, Role role) {
        if (userRepository.findByFirebaseUid(uid).isPresent()) return;

        UserEntity user = new UserEntity();
        user.setFirebaseUid(uid);
        user.setFirstName("Dev");
        user.setLastName(role.name());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.VERIFIED);
        user.setCountry("FR");
        user.setRoles(role == Role.ADMIN
                ? Set.of(Role.SENDER, Role.TRAVELER, Role.ADMIN)
                : Set.of(role));
        userRepository.save(user);
    }

    private String exchangeForIdToken(String customToken) {
        record ExchangeRequest(String token, boolean returnSecureToken) {}
        record FirebaseTokenResponse(String idToken) {}

        FirebaseTokenResponse response = restClient.post()
                .uri(FIREBASE_TOKEN_URL + firebaseWebApiKey)
                .body(new ExchangeRequest(customToken, true))
                .retrieve()
                .body(FirebaseTokenResponse.class);

        if (response == null || response.idToken() == null) {
            throw new IllegalStateException("Échec de l'échange du custom token Firebase — vérifiez FIREBASE_WEB_API_KEY");
        }
        return response.idToken();
    }
}
