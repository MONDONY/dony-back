package com.dony.api.auth;

import com.google.firebase.auth.FirebaseToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * Résout un UserEntity à partir d'un token Firebase.
 * Fallback par numéro de téléphone quand le firebase_uid ne correspond à aucun user connu
 * (cas d'un utilisateur créé via email OTP qui se reconnecte ensuite via SMS OTP).
 * Si le fallback réussit, met à jour firebase_uid en base pour que les prochaines
 * connexions par téléphone fonctionnent sans passer par ce fallback.
 */
@Service
public class UserLinkerService {

    private static final Logger log = LoggerFactory.getLogger(UserLinkerService.class);

    private final UserRepository userRepository;

    public UserLinkerService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public Optional<UserEntity> resolveAndLink(String newFirebaseUid, FirebaseToken token) {
        Optional<UserEntity> direct = userRepository.findByFirebaseUid(newFirebaseUid);
        if (direct.isPresent()) {
            return direct;
        }

        String provider = extractProvider(token);
        if ("phone".equals(provider)) {
            String phoneNumber = extractPhoneNumber(token);
            if (phoneNumber != null) {
                return userRepository.findByPhoneNumber(phoneNumber).map(user -> {
                    log.info("Phone account linking: uid {} → existing user {} (was {})",
                            newFirebaseUid, user.getId(), user.getFirebaseUid());
                    user.setFirebaseUid(newFirebaseUid);
                    return userRepository.save(user);
                });
            }
        }

        return Optional.empty();
    }

    private String extractProvider(FirebaseToken token) {
        Object firebaseClaim = token.getClaims().get("firebase");
        if (firebaseClaim instanceof Map<?, ?> map) {
            Object provider = map.get("sign_in_provider");
            if (provider instanceof String s) return s;
        }
        return null;
    }

    private String extractPhoneNumber(FirebaseToken token) {
        Object claim = token.getClaims().get("phone_number");
        return claim instanceof String s ? s : null;
    }
}
