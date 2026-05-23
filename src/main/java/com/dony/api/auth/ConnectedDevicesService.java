package com.dony.api.auth;

import com.dony.api.auth.dto.UserDeviceDto;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ConnectedDevicesService {

    private static final Logger log = LoggerFactory.getLogger(ConnectedDevicesService.class);

    private final UserDeviceJpaRepository deviceRepo;
    private final UserRepository userRepository;
    private final Optional<FirebaseAuth> firebaseAuth;

    public ConnectedDevicesService(
            UserDeviceJpaRepository deviceRepo,
            UserRepository userRepository,
            Optional<FirebaseAuth> firebaseAuth) {
        this.deviceRepo = deviceRepo;
        this.userRepository = userRepository;
        this.firebaseAuth = firebaseAuth;
    }

    @Transactional(readOnly = true)
    public List<UserDeviceDto> listDevices(UUID userId, String currentDeviceId) {
        return deviceRepo.findByUserIdOrderByLastSeenAtDesc(userId).stream()
                .map(d -> new UserDeviceDto(
                        d.getDeviceId(),
                        d.getDeviceName(),
                        d.getPlatform(),
                        d.getLastSeenAt(),
                        d.getDeviceId().equals(currentDeviceId)
                ))
                .toList();
    }

    @Transactional
    public void revokeDevice(UUID userId, String deviceId, String currentDeviceId) {
        if (deviceId.equals(currentDeviceId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Impossible de révoquer l'appareil courant");
        }
        int deleted = deviceRepo.deleteByUserIdAndDeviceId(userId, deviceId);
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Appareil introuvable");
        }
    }

    @Transactional
    public void revokeOthers(UUID userId, String currentDeviceId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur introuvable"));
        if (firebaseAuth.isEmpty()) {
            log.warn("FirebaseAuth non disponible — révocation des tokens Firebase ignorée pour userId={}", userId);
        } else {
            try {
                firebaseAuth.get().revokeRefreshTokens(user.getFirebaseUid());
            } catch (FirebaseAuthException e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Erreur lors de la révocation Firebase");
            }
        }
        deviceRepo.deleteByUserIdAndDeviceIdNot(userId, currentDeviceId);
    }

    @Transactional
    public void upsertDevice(UUID userId, String deviceId, String deviceName,
                              String platform, String fcmToken) {
        deviceRepo.findByUserIdAndDeviceId(userId, deviceId).ifPresentOrElse(
                existing -> {
                    existing.setDeviceName(deviceName);
                    existing.setFcmToken(fcmToken);
                    existing.setPlatform(platform);
                    deviceRepo.save(existing);
                },
                () -> {
                    UserDeviceEntity entity = new UserDeviceEntity();
                    entity.setUserId(userId);
                    entity.setDeviceId(deviceId);
                    entity.setDeviceName(deviceName);
                    entity.setPlatform(platform);
                    entity.setFcmToken(fcmToken);
                    deviceRepo.save(entity);
                }
        );
    }
}
