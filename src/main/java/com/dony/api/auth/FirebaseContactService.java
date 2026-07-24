package com.dony.api.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Source de vérité des coordonnées de contact (téléphone, email) : Firebase Auth,
 * PAS la base dony. On ne stocke plus phone/email localement — un vol de la base
 * ne peut donc plus les révéler. Ce service les récupère à la demande via l'Admin
 * SDK, à partir de l'UID Firebase (déjà stocké localement).
 *
 * <p>Un cache mémoire court évite de frapper Firebase à chaque lecture (ex. envoi
 * SMS). Il ne contient que de la PII TRANSITOIRE en RAM (jamais persistée), et est
 * invalidé dès qu'une coordonnée change.
 */
@Service
public class FirebaseContactService {

    private static final Logger log = LoggerFactory.getLogger(FirebaseContactService.class);

    /** Coordonnées de contact d'un utilisateur (peuvent être nulles). */
    public record Contact(String phoneNumber, String email) {
        public static final Contact EMPTY = new Contact(null, null);
    }

    private final FirebaseAuth firebaseAuth;
    private final Cache<String, Contact> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    public FirebaseContactService(FirebaseAuth firebaseAuth) {
        this.firebaseAuth = firebaseAuth;
    }

    /**
     * Coordonnées de l'utilisateur pour un UID Firebase. Échec réseau / UID
     * inconnu → {@link Contact#EMPTY} (dégradation gracieuse, jamais d'exception
     * propagée pour ne pas casser un envoi SMS ou une réponse d'API).
     */
    public Contact getContact(String firebaseUid) {
        if (firebaseUid == null || firebaseUid.isBlank()) {
            return Contact.EMPTY;
        }
        return cache.get(firebaseUid, uid -> {
            try {
                UserRecord record = firebaseAuth.getUser(uid);
                return new Contact(record.getPhoneNumber(), record.getEmail());
            } catch (FirebaseAuthException e) {
                log.warn("Firebase getUser({}) a échoué : {}", uid, e.getAuthErrorCode());
                return Contact.EMPTY;
            } catch (Exception e) {
                log.warn("Firebase getUser({}) indisponible", uid, e);
                return Contact.EMPTY;
            }
        });
    }

    /** UID Firebase possédant cet email (lookup exact), ou vide si aucun. */
    public Optional<String> findUidByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(firebaseAuth.getUserByEmail(email).getUid());
        } catch (FirebaseAuthException e) {
            return Optional.empty();
        }
    }

    /** UID Firebase possédant ce numéro (lookup exact), ou vide si aucun. */
    public Optional<String> findUidByPhone(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(firebaseAuth.getUserByPhoneNumber(phoneNumber).getUid());
        } catch (FirebaseAuthException e) {
            return Optional.empty();
        }
    }

    /** Met à jour l'email côté Firebase (source de vérité) et invalide le cache. */
    public void updateEmail(String firebaseUid, String email) {
        try {
            firebaseAuth.updateUser(new UserRecord.UpdateRequest(firebaseUid).setEmail(email));
            cache.invalidate(firebaseUid);
        } catch (FirebaseAuthException e) {
            throw new IllegalStateException("Mise à jour email Firebase impossible", e);
        }
    }

    /** Met à jour le téléphone côté Firebase (source de vérité) et invalide le cache. */
    public void updatePhone(String firebaseUid, String phoneNumber) {
        try {
            firebaseAuth.updateUser(new UserRecord.UpdateRequest(firebaseUid).setPhoneNumber(phoneNumber));
            cache.invalidate(firebaseUid);
        } catch (FirebaseAuthException e) {
            throw new IllegalStateException("Mise à jour téléphone Firebase impossible", e);
        }
    }

    /** Invalide l'entrée cache (à appeler après toute mutation externe). */
    public void evict(String firebaseUid) {
        if (firebaseUid != null) {
            cache.invalidate(firebaseUid);
        }
    }
}
