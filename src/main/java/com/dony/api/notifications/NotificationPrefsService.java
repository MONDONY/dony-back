package com.dony.api.notifications;

import com.dony.api.auth.UserRepository;
import com.dony.api.common.DonyBusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class NotificationPrefsService {

    private static final Set<String> CRITICAL_TYPES = Set.of(
            "PAYMENT_RELEASED", "DELIVERY_CONFIRMED", "DISPUTE_OPENED"
    );

    private static final Map<String, String> TYPE_TO_PREF = Map.ofEntries(
            Map.entry("BID_CREATED",                  "pushActivityBids"),
            Map.entry("BID_ACCEPTED",                 "pushActivityBids"),
            Map.entry("BID_REJECTED",                 "pushActivityBids"),
            Map.entry("PARCEL_REFUSED",               "pushActivityBids"),
            Map.entry("BID_EXPIRED",                  "pushActivityBids"),
            Map.entry("TRIP_CANCELLED",               "pushActivityBids"),
            Map.entry("negotiation_started",          "pushActivityNegotiations"),
            Map.entry("negotiation_counter",          "pushActivityNegotiations"),
            Map.entry("negotiation_awaiting_trip",    "pushActivityNegotiations"),
            Map.entry("negotiation_awaiting_payment", "pushActivityNegotiations"),
            Map.entry("request_accepted",             "pushActivityNegotiations"),
            Map.entry("request_expired",              "pushActivityNegotiations"),
            Map.entry("negotiation_expired",          "pushActivityNegotiations"),
            Map.entry("NEW_MESSAGE",                  "pushMessages"),
            Map.entry("TRIP_IN_PROGRESS",             "pushTripReminder"),
            Map.entry("PROMO",                        "pushPromo")
    );

    private final NotificationPrefsJpaRepository repository;
    private final UserRepository userRepository;

    public NotificationPrefsService(NotificationPrefsJpaRepository repository,
                                    UserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public NotificationPrefsDto getPrefs(String firebaseUid) {
        UUID userId = resolveUserId(firebaseUid);
        return repository.findById(userId)
                .map(this::toDto)
                .orElse(NotificationPrefsDto.defaults());
    }

    public void upsert(String firebaseUid, NotificationPrefsDto dto) {
        UUID userId = resolveUserId(firebaseUid);
        NotificationPrefsEntity entity = repository.findById(userId)
                .orElseGet(() -> {
                    NotificationPrefsEntity e = new NotificationPrefsEntity();
                    e.setUserId(userId);
                    return e;
                });
        entity.setPushActivityBids(dto.pushActivityBids());
        entity.setPushActivityNegotiations(dto.pushActivityNegotiations());
        entity.setPushMessages(dto.pushMessages());
        entity.setPushTripReminder(dto.pushTripReminder());
        entity.setPushPromo(dto.pushPromo());
        repository.save(entity);
    }

    @Transactional(readOnly = true)
    public boolean isAllowed(UUID userId, String notificationType) {
        if (notificationType == null) return true;
        if (CRITICAL_TYPES.contains(notificationType)) return true;
        String prefKey = TYPE_TO_PREF.get(notificationType);
        if (prefKey == null) return true;
        return repository.findById(userId)
                .map(prefs -> getPrefValue(prefs, prefKey))
                .orElse(true);
    }

    private boolean getPrefValue(NotificationPrefsEntity prefs, String prefKey) {
        return switch (prefKey) {
            case "pushActivityBids"         -> prefs.isPushActivityBids();
            case "pushActivityNegotiations" -> prefs.isPushActivityNegotiations();
            case "pushMessages"             -> prefs.isPushMessages();
            case "pushTripReminder"         -> prefs.isPushTripReminder();
            case "pushPromo"                -> prefs.isPushPromo();
            default                         -> true;
        };
    }

    private UUID resolveUserId(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .map(u -> u.getId())
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "user_not_found",
                        "User not found", "Utilisateur introuvable"));
    }

    private NotificationPrefsDto toDto(NotificationPrefsEntity e) {
        return new NotificationPrefsDto(
                e.isPushActivityBids(),
                e.isPushActivityNegotiations(),
                e.isPushMessages(),
                e.isPushTripReminder(),
                e.isPushPromo()
        );
    }
}
