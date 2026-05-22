package com.dony.api.auth;

import com.dony.api.auth.dto.UserDeviceDto;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConnectedDevicesServiceTest {

    @Mock UserDeviceJpaRepository deviceRepo;
    @Mock UserRepository userRepository;
    @Mock FirebaseAuth firebaseAuth;

    ConnectedDevicesService service;
    UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ConnectedDevicesService(deviceRepo, userRepository, Optional.of(firebaseAuth));
    }

    @Test
    void listDevices_retourneListeAvecFlagIsCurrent() {
        String currentDeviceId = "device-abc";
        UserDeviceEntity dev1 = deviceEntity(userId, "device-abc", "iPhone 14", "ios");
        UserDeviceEntity dev2 = deviceEntity(userId, "device-xyz", "Galaxy S22", "android");
        when(deviceRepo.findByUserIdOrderByLastSeenAtDesc(userId)).thenReturn(List.of(dev1, dev2));

        List<UserDeviceDto> result = service.listDevices(userId, currentDeviceId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).isCurrent()).isTrue();
        assertThat(result.get(1).isCurrent()).isFalse();
    }

    @Test
    void revokeDevice_supprimeLaLigne() {
        String deviceId = "device-xyz";
        when(deviceRepo.deleteByUserIdAndDeviceId(userId, deviceId)).thenReturn(1);

        service.revokeDevice(userId, deviceId, "device-abc");

        verify(deviceRepo).deleteByUserIdAndDeviceId(userId, deviceId);
    }

    @Test
    void revokeDevice_lanceExceptionSiAppareilCourant() {
        assertThatThrownBy(() -> service.revokeDevice(userId, "device-abc", "device-abc"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void revokeDevice_lanceExceptionSiNonTrouve() {
        when(deviceRepo.deleteByUserIdAndDeviceId(userId, "device-xyz")).thenReturn(0);
        assertThatThrownBy(() -> service.revokeDevice(userId, "device-xyz", "device-abc"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void revokeOthers_appelleFirebaseEtSupprimeLesAutres() throws Exception {
        String currentDeviceId = "device-abc";
        UserEntity user = new UserEntity();
        user.setFirebaseUid("uid-123");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        service.revokeOthers(userId, currentDeviceId);

        verify(firebaseAuth).revokeRefreshTokens("uid-123");
        verify(deviceRepo).deleteByUserIdAndDeviceIdNot(userId, currentDeviceId);
    }

    @Test
    void upsertDevice_creeLEntitesSiAbsente() {
        when(deviceRepo.findByUserIdAndDeviceId(userId, "new-dev")).thenReturn(Optional.empty());
        when(deviceRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.upsertDevice(userId, "new-dev", "iPhone 15", "ios", "token123");

        verify(deviceRepo).save(argThat(e ->
            e.getDeviceId().equals("new-dev") && e.getDeviceName().equals("iPhone 15")
        ));
    }

    private UserDeviceEntity deviceEntity(UUID userId, String deviceId, String name, String platform) {
        UserDeviceEntity e = new UserDeviceEntity();
        e.setId(UUID.randomUUID());
        e.setUserId(userId);
        e.setDeviceId(deviceId);
        e.setDeviceName(name);
        e.setPlatform(platform);
        e.setLastSeenAt(OffsetDateTime.now());
        e.setCreatedAt(OffsetDateTime.now());
        return e;
    }
}
