package com.dony.api.auth;

import com.google.firebase.auth.FirebaseToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Résout un UserEntity à partir d'un token Firebase.
 *
 * <p>Le rattachement se fait uniquement par {@code firebase_uid}. L'ancien repli par
 * numéro de téléphone n'est plus possible : le numéro n'existe plus en base (il vit
 * dans Firebase, voir {@link FirebaseContactService}) et l'interroger côté Firebase
 * renverrait l'UID du compte qui vient justement de s'authentifier, donc rien d'utile.
 * Un utilisateur créé par email OTP qui se reconnecte ensuite par SMS OTP obtient donc
 * un compte Firebase distinct — la fusion des deux relève de Firebase, pas de Yadony.
 */
@Service
public class UserLinkerService {

    private final UserRepository userRepository;

    public UserLinkerService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public Optional<UserEntity> resolveAndLink(String newFirebaseUid, FirebaseToken token) {
        return userRepository.findByFirebaseUid(newFirebaseUid);
    }
}
