package com.dony.api.auth;

import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class DevTokenControllerTest {

    @Mock UserRepository userRepository;
    @Mock FirebaseAuth firebaseAuth;

    @Test
    void getDevToken_throwsWhenFirebaseNull() {
        DevTokenController controller = new DevTokenController(userRepository, null, "fake-api-key");

        assertThatThrownBy(() -> controller.getDevToken(Role.SENDER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Firebase non initialisé");
    }

    @Test
    void getDevToken_throwsWhenWebApiKeyBlank() {
        DevTokenController controller = new DevTokenController(userRepository, firebaseAuth, "");

        assertThatThrownBy(() -> controller.getDevToken(Role.SENDER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FIREBASE_WEB_API_KEY");
    }
}
