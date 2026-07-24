package com.dony.api.auth;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("FirebaseContactService — contact via Firebase (jamais stocké en base)")
class FirebaseContactServiceTest {

    private FirebaseAuth firebaseAuth;
    private FirebaseContactService service;

    @BeforeEach
    void setUp() {
        firebaseAuth = mock(FirebaseAuth.class);
        service = new FirebaseContactService(firebaseAuth);
    }

    @Test
    @DisplayName("getContact → phone + email depuis Firebase, mis en cache (1 seul appel)")
    void getContact_returnsAndCaches() throws Exception {
        UserRecord record = mock(UserRecord.class);
        when(record.getPhoneNumber()).thenReturn("+221701234567");
        when(record.getEmail()).thenReturn("awa@example.com");
        when(firebaseAuth.getUser("uid-1")).thenReturn(record);

        FirebaseContactService.Contact c1 = service.getContact("uid-1");
        FirebaseContactService.Contact c2 = service.getContact("uid-1");

        assertThat(c1.phoneNumber()).isEqualTo("+221701234567");
        assertThat(c1.email()).isEqualTo("awa@example.com");
        assertThat(c2).isEqualTo(c1);
        verify(firebaseAuth, times(1)).getUser("uid-1"); // 2e lecture servie par le cache
    }

    @Test
    @DisplayName("getContact → EMPTY (dégradation gracieuse) si Firebase échoue")
    void getContact_gracefulOnFailure() throws Exception {
        when(firebaseAuth.getUser("uid-x")).thenThrow(mock(FirebaseAuthException.class));

        assertThat(service.getContact("uid-x")).isEqualTo(FirebaseContactService.Contact.EMPTY);
    }

    @Test
    @DisplayName("getContact → EMPTY si UID null/vide, sans appeler Firebase")
    void getContact_nullUid() throws Exception {
        assertThat(service.getContact(null)).isEqualTo(FirebaseContactService.Contact.EMPTY);
        assertThat(service.getContact("")).isEqualTo(FirebaseContactService.Contact.EMPTY);
        verify(firebaseAuth, times(0)).getUser(anyString());
    }

    @Test
    @DisplayName("findUidByEmail → UID si trouvé, vide sinon")
    void findUidByEmail() throws Exception {
        UserRecord record = mock(UserRecord.class);
        when(record.getUid()).thenReturn("uid-42");
        when(firebaseAuth.getUserByEmail("awa@example.com")).thenReturn(record);
        when(firebaseAuth.getUserByEmail("ghost@example.com")).thenThrow(mock(FirebaseAuthException.class));

        assertThat(service.findUidByEmail("awa@example.com")).contains("uid-42");
        assertThat(service.findUidByEmail("ghost@example.com")).isEmpty();
        assertThat(service.findUidByEmail(null)).isEmpty();
    }

    @Test
    @DisplayName("findUidByPhone → UID si trouvé, vide sinon")
    void findUidByPhone() throws Exception {
        UserRecord record = mock(UserRecord.class);
        when(record.getUid()).thenReturn("uid-7");
        when(firebaseAuth.getUserByPhoneNumber("+221701234567")).thenReturn(record);

        assertThat(service.findUidByPhone("+221701234567")).contains("uid-7");
        assertThat(service.findUidByPhone("")).isEmpty();
    }
}
