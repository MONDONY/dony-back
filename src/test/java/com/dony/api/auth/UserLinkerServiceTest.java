package com.dony.api.auth;

import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserLinkerService — résolution de compte par UID Firebase")
class UserLinkerServiceTest {

    @Mock private UserRepository userRepository;
    @InjectMocks private UserLinkerService userLinkerService;

    private static final String NEW_UID    = "new-phone-uid-abc";
    private static final String PHONE      = "+33612345678";

    private UserEntity makeUser(String firebaseUid) {
        UserEntity u = new UserEntity();
        setId(u, UUID.randomUUID());
        u.setFirebaseUid(firebaseUid);
        u.setStatus(UserStatus.ACTIVE);
        return u;
    }

    private FirebaseToken phoneToken() {
        FirebaseToken t = mock(FirebaseToken.class);
        lenient().when(t.getClaims()).thenReturn(Map.of(
                "firebase", Map.of("sign_in_provider", "phone"),
                "phone_number", PHONE
        ));
        return t;
    }

    @Test
    @DisplayName("firebase_uid connu → retourne directement le user sans modifier ni inspecter le token")
    void directMatch_returnsUser() {
        UserEntity user = makeUser(NEW_UID);
        when(userRepository.findByFirebaseUid(NEW_UID)).thenReturn(Optional.of(user));
        // Token jamais inspecté : l'UID suffit
        FirebaseToken anyToken = mock(FirebaseToken.class);

        Optional<UserEntity> result = userLinkerService.resolveAndLink(NEW_UID, anyToken);

        assertThat(result).contains(user);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("uid inconnu → vide, sans repli par téléphone (le numéro n'est plus en base)")
    void unknownUid_returnsEmpty_noPhoneFallback() {
        when(userRepository.findByFirebaseUid(NEW_UID)).thenReturn(Optional.empty());

        Optional<UserEntity> result = userLinkerService.resolveAndLink(NEW_UID, phoneToken());

        assertThat(result).isEmpty();
        // Aucune ré-écriture d'UID : la fusion de deux comptes Firebase relève de Firebase
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("provider non-phone (google), uid inconnu → vide")
    void nonPhoneProvider_unknownUid_returnsEmpty() {
        when(userRepository.findByFirebaseUid(NEW_UID)).thenReturn(Optional.empty());
        FirebaseToken t = mock(FirebaseToken.class);

        Optional<UserEntity> result = userLinkerService.resolveAndLink(NEW_UID, t);

        assertThat(result).isEmpty();
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("token null → vide, sans exception (le token n'est jamais lu)")
    void nullToken_returnsEmpty() {
        when(userRepository.findByFirebaseUid(NEW_UID)).thenReturn(Optional.empty());

        Optional<UserEntity> result = userLinkerService.resolveAndLink(NEW_UID, null);

        assertThat(result).isEmpty();
    }

    private static void setId(Object entity, UUID id) {
        try {
            Class<?> c = entity.getClass();
            while (c != null) {
                try { Field f = c.getDeclaredField("id"); f.setAccessible(true); f.set(entity, id); return; }
                catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            }
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
