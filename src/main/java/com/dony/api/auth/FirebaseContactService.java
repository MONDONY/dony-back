package com.dony.api.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UidIdentifier;
import com.google.firebase.auth.UserIdentifier;
import com.google.firebase.auth.UserRecord;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /** Plafond imposé par l'API Firebase {@code getUsers}. */
    private static final int BATCH_SIZE = 100;

    /** Coordonnées de contact d'un utilisateur (peuvent être nulles). */
    public record Contact(String phoneNumber, String email) {
        public static final Contact EMPTY = new Contact(null, null);
    }

    private final FirebaseAuth firebaseAuth;
    private final Cache<String, Contact> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    // required = false : en test/CI, FirebaseConfig ne publie aucun FirebaseAuth
    // (pas de credentials). Le service dégrade alors vers Contact.EMPTY plutôt que
    // d'empêcher le contexte Spring de démarrer.
    public FirebaseContactService(@Autowired(required = false) FirebaseAuth firebaseAuth) {
        this.firebaseAuth = firebaseAuth;
    }

    /** Firebase indisponible (test/CI) : aucune coordonnée n'est lisible ni modifiable. */
    private boolean unavailable() {
        return firebaseAuth == null;
    }

    /**
     * Coordonnées de l'utilisateur pour un UID Firebase. Échec réseau / UID
     * inconnu → {@link Contact#EMPTY} (dégradation gracieuse, jamais d'exception
     * propagée pour ne pas casser un envoi SMS ou une réponse d'API).
     */
    public Contact getContact(String firebaseUid) {
        if (unavailable() || firebaseUid == null || firebaseUid.isBlank()) {
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

    /**
     * Coordonnées de plusieurs utilisateurs en un aller-retour (listes admin, exports).
     * Firebase plafonne à 100 identifiants par appel, d'où le découpage. Les UID absents
     * de la réponse retombent sur {@link Contact#EMPTY}, jamais sur une exception.
     */
    public Map<String, Contact> getContacts(Collection<String> firebaseUids) {
        Map<String, Contact> result = new HashMap<>();
        if (unavailable() || firebaseUids == null || firebaseUids.isEmpty()) {
            return result;
        }
        List<String> missing = new ArrayList<>();
        for (String uid : firebaseUids) {
            if (uid == null || uid.isBlank() || result.containsKey(uid)) {
                continue;
            }
            Contact cached = cache.getIfPresent(uid);
            if (cached != null) {
                result.put(uid, cached);
            } else {
                missing.add(uid);
            }
        }
        for (int i = 0; i < missing.size(); i += BATCH_SIZE) {
            List<String> chunk = missing.subList(i, Math.min(i + BATCH_SIZE, missing.size()));
            fetchChunk(chunk, result);
        }
        for (String uid : missing) {
            result.putIfAbsent(uid, Contact.EMPTY);
        }
        return result;
    }

    private void fetchChunk(List<String> chunk, Map<String, Contact> result) {
        try {
            List<UserIdentifier> identifiers = chunk.stream()
                    .map(uid -> (UserIdentifier) new UidIdentifier(uid))
                    .toList();
            for (UserRecord record : firebaseAuth.getUsers(identifiers).getUsers()) {
                Contact contact = new Contact(record.getPhoneNumber(), record.getEmail());
                cache.put(record.getUid(), contact);
                result.put(record.getUid(), contact);
            }
        } catch (Exception e) {
            log.warn("Firebase getUsers({} uids) indisponible", chunk.size(), e);
        }
    }

    /** UID Firebase possédant cet email (lookup exact), ou vide si aucun. */
    public Optional<String> findUidByEmail(String email) {
        if (unavailable() || email == null || email.isBlank()) {
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
        if (unavailable() || phoneNumber == null || phoneNumber.isBlank()) {
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
        if (unavailable()) {
            return;
        }
        try {
            firebaseAuth.updateUser(new UserRecord.UpdateRequest(firebaseUid).setEmail(email));
            cache.invalidate(firebaseUid);
        } catch (FirebaseAuthException e) {
            throw new IllegalStateException("Mise à jour email Firebase impossible", e);
        }
    }

    /** Met à jour le téléphone côté Firebase (source de vérité) et invalide le cache. */
    public void updatePhone(String firebaseUid, String phoneNumber) {
        if (unavailable()) {
            return;
        }
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
